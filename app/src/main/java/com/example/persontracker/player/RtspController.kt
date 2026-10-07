package com.example.persontracker.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.example.persontracker.domain.RtspState
import com.example.persontracker.domain.RtspStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * يلفّ ExoPlayer لبثّ RTSP بأقل تأخير ممكن، مع:
 *  - مخزن مؤقت صغير جدًا + RTP عبر TCP (اختياري) لثبات الاتصال.
 *  - تعطيل الصوت (توفير CPU/بطارية).
 *  - إعادة اتصال تلقائية بتأخير تصاعدي (1، 2، 4، 8، 10 ثوانٍ) + مراقب «تجمّد» + استعادة فورية عند عودة الشبكة.
 * يجب استدعاء كل الدوال العامة من الخيط الرئيسي.
 */
@OptIn(UnstableApi::class)
class RtspController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(RtspStatus())
    val status: StateFlow<RtspStatus> = _status.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    /** عدّاد الإطارات المعروضة (يُصفَّر من حلقة الإحصاءات لحساب FPS). */
    val frameCounter = AtomicInteger(0)
    @Volatile private var lastFrameMs = 0L

    private var url = ""
    private var forceTcp = true
    var autoReconnect = true

    private var attempt = 0
    private var hasRendered = false
    private var statusSince = 0L
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkRegistered = false

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                // min, max, bufferForPlayback, bufferForPlaybackAfterRebuffer (ms) — قيم منخفضة للتأخير الأدنى
                .setBufferDurationsMs(300, 800, 100, 200)
                .build()
        )
        .build()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING ->
                    if (hasRendered) setStatus(RtspState.BUFFERING, "تخزين مؤقت…")
                    else setStatus(RtspState.CONNECTING, "جارٍ الاتصال بالكاميرا…")
                Player.STATE_READY -> {
                    hasRendered = true
                    attempt = 0
                    lastFrameMs = SystemClock.elapsedRealtime()
                    setStatus(RtspState.PLAYING, "")
                }
                Player.STATE_ENDED -> scheduleReconnect("انتهى البث")
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleReconnect(describe(error))
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                _videoAspect.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { onNetworkBack() }
        }
    }

    init {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.volume = 0f
        player.playWhenReady = true
        player.addListener(listener)
        player.setVideoFrameMetadataListener(VideoFrameMetadataListener { _, _, _, _ ->
            frameCounter.incrementAndGet()
            lastFrameMs = SystemClock.elapsedRealtime()
        })
    }

    @MainThread
    fun startNetworkMonitor() {
        if (networkRegistered) return
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
            networkRegistered = true
        } catch (_: Exception) {
            // غير حرج: يبقى مراقب التجمّد وإعادة الاتصال الدورية يعملان
        }
    }

    /** يبدأ (أو يعيد بدء) البث بالإعدادات المعطاة. */
    @MainThread
    fun start(url: String, forceTcp: Boolean, autoReconnect: Boolean) {
        this.url = url.trim()
        this.forceTcp = forceTcp
        this.autoReconnect = autoReconnect
        attempt = 0
        connect()
    }

    @MainThread
    fun reconnectNow() {
        attempt = 0
        connect()
    }

    @MainThread
    fun stop() {
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        player.stop()
        player.clearMediaItems()
        hasRendered = false
        setStatus(RtspState.IDLE, "")
    }

    @MainThread
    fun release() {
        stop()
        if (networkRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
            networkRegistered = false
        }
        player.removeListener(listener)
        player.release()
    }

    // ------------------------------------------------------------------------------------

    private fun connect() {
        reconnectJob?.cancel(); reconnectJob = null
        if (!url.startsWith("rtsp://", ignoreCase = true) && !url.startsWith("rtsps://", ignoreCase = true)) {
            setStatus(RtspState.ERROR, "عنوان RTSP غير صالح — يجب أن يبدأ بـ rtsp://")
            return
        }
        hasRendered = false
        lastFrameMs = SystemClock.elapsedRealtime()
        setStatus(RtspState.CONNECTING, if (attempt > 0) "إعادة المحاولة #$attempt…" else "جارٍ الاتصال بالكاميرا…")
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(forceTcp)
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        startWatchdog()
    }

    private fun scheduleReconnect(reason: String) {
        player.stop()
        if (!autoReconnect) {
            setStatus(RtspState.ERROR, reason)
            return
        }
        attempt++
        val delayMs = min(1_000L shl min(attempt - 1, 4), 10_000L)
        setStatus(
            RtspState.RECONNECTING,
            "$reason — إعادة المحاولة خلال ${delayMs / 1000} ث (محاولة $attempt)",
        )
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            reconnectJob = null
            connect()
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val now = SystemClock.elapsedRealtime()
                when (_status.value.state) {
                    RtspState.PLAYING ->
                        if (now - lastFrameMs > 6_000) scheduleReconnect("توقّف وصول الإطارات")
                    RtspState.CONNECTING, RtspState.BUFFERING ->
                        if (now - statusSince > 15_000) scheduleReconnect("انتهت مهلة الاتصال")
                    else -> Unit
                }
            }
        }
    }

    private fun onNetworkBack() {
        val st = _status.value.state
        if (url.isNotBlank() && autoReconnect && (st == RtspState.RECONNECTING || st == RtspState.ERROR)) {
            attempt = 0
            connect()
        }
    }

    private fun setStatus(state: RtspState, message: String) {
        if (_status.value.state != state) statusSince = SystemClock.elapsedRealtime()
        _status.value = RtspStatus(state, message, attempt)
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "تعذّر الوصول إلى الكاميرا"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "تعذّر فكّ ترميز الفيديو (جرّب بثًّا فرعيًا H.264)"
        else -> "خطأ في البث (${e.errorCodeName})"
    }
}
