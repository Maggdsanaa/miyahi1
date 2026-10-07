package com.example.persontracker.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import androidx.annotation.MainThread
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * يشغّل بثّ RTSP عبر libVLC (بدل Media3) لأنه يتحمّل أجهزة Hikvision التي ترسل H.265 دون
 * سطر `a=fmtp` في وصف SDP (Media3 يرفض ذلك بالرسالة «missing attribute fmtp»).
 *
 *  - تخزين مؤقت صغير + RTP عبر TCP (اختياري) + فكّ ترميز عتادي عند توفره.
 *  - الصوت معطّل.
 *  - إعادة اتصال تلقائية بتأخير تصاعدي + مراقب «تجمّد» + استعادة فورية عند عودة الشبكة.
 * يجب استدعاء كل الدوال العامة من الخيط الرئيسي.
 *
 * الواجهة العامة مطابقة للنسخة السابقة، فلا يتغيّر MainViewModel.
 */
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
    private var lastDisplayed = 0
    private var statsWorking = false

    private var url = ""
    private var forceTcp = true
    var autoReconnect = true

    private var attempt = 0
    private var hasRendered = false
    private var statusSince = 0L
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var networkRegistered = false

    private val libVlc = LibVLC(context, arrayListOf("--no-audio"))
    private val player = MediaPlayer(libVlc)

    private var attachedView: TextureView? = null
    private var layoutListener: View.OnLayoutChangeListener? = null

    private val videoLayoutListener = IVLCVout.OnNewVideoLayoutListener { _, _, _, visW, visH, sarNum, sarDen ->
        if (visW > 0 && visH > 0) {
            val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen else 1f
            _videoAspect.value = visW * sar / visH
        }
    }

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { onNetworkBack() }
        }
    }

    init {
        player.setEventListener(MediaPlayer.EventListener { ev ->
            // الحدث يُعاد استخدامه داخليًا: انسخ القيم قبل النشر إلى الخيط الرئيسي
            val type = ev.type
            val voutCount = ev.voutCount
            scope.launch { onPlayerEvent(type, voutCount) }
        })
    }

    // ------------------------------------------------------------------------------------
    // ربط شاشة العرض (TextureView — مطلوب لالتقاط إطارات الاكتشاف عبر getBitmap)

    @MainThread
    fun attachView(tv: TextureView) {
        detachView()
        attachedView = tv
        val vout = player.vlcVout
        vout.setVideoView(tv)
        vout.attachViews(videoLayoutListener)
        player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL // الصندوق نفسه بنسبة الفيديو
        val l = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0) vout.setWindowSize(v.width, v.height)
        }
        layoutListener = l
        tv.addOnLayoutChangeListener(l)
        if (tv.width > 0 && tv.height > 0) vout.setWindowSize(tv.width, tv.height)
    }

    @MainThread
    fun detachView() {
        layoutListener?.let { attachedView?.removeOnLayoutChangeListener(it) }
        layoutListener = null
        attachedView = null
        val vout = player.vlcVout
        if (vout.areViewsAttached()) vout.detachViews()
    }

    // ------------------------------------------------------------------------------------

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
        hasRendered = false
        setStatus(RtspState.IDLE, "")
        try { player.stop() } catch (_: Exception) {}
    }

    @MainThread
    fun release() {
        stop()
        if (networkRegistered) {
            try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
            networkRegistered = false
        }
        detachView()
        player.setEventListener(null)
        player.release()
        libVlc.release()
    }

    // ------------------------------------------------------------------------------------

    private fun connect() {
        reconnectJob?.cancel(); reconnectJob = null
        if (!url.startsWith("rtsp://", ignoreCase = true) && !url.startsWith("rtsps://", ignoreCase = true)) {
            setStatus(RtspState.ERROR, "عنوان RTSP غير صالح — يجب أن يبدأ بـ rtsp://")
            return
        }
        hasRendered = false
        statsWorking = false
        lastDisplayed = 0
        lastFrameMs = SystemClock.elapsedRealtime()
        // تدوير الاستراتيجية مع كل إخفاق: (النقل المختار + فكّ عتادي) ثم (برمجي) ثم النقل الآخر (عتادي ثم برمجي)
        val idx = attempt % 4
        val tcp = if (idx < 2) forceTcp else !forceTcp
        val hw = idx % 2 == 0
        val mode = (if (tcp) "TCP" else "UDP") + (if (hw) " · عتادي" else " · برمجي")
        setStatus(RtspState.CONNECTING, if (attempt > 0) "إعادة المحاولة #$attempt ($mode)…" else "جارٍ الاتصال بالكاميرا…")
        try { player.stop() } catch (_: Exception) {}
        val media = Media(libVlc, Uri.parse(normalizeRtspUrl(url)))
        media.setHWDecoderEnabled(hw, false)
        media.addOption(":network-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        media.addOption(":no-audio")
        if (tcp) media.addOption(":rtsp-tcp")
        player.media = media
        media.release() // المشغّل يحتفظ بنسخته
        player.play()
        startWatchdog()
    }

    private fun onPlayerEvent(type: Int, voutCount: Int) {
        val st = _status.value.state
        when (type) {
            MediaPlayer.Event.Opening ->
                if (st != RtspState.PLAYING) setStatus(RtspState.CONNECTING, "جارٍ الاتصال بالكاميرا…")
            MediaPlayer.Event.Vout ->
                if (voutCount > 0) markPlaying()
            MediaPlayer.Event.EncounteredError ->
                if (st != RtspState.RECONNECTING && st != RtspState.IDLE)
                    scheduleReconnect("تعذّر تشغيل البث — تحقق من العنوان وكلمة المرور والترميز")
            MediaPlayer.Event.EndReached ->
                if (st != RtspState.RECONNECTING && st != RtspState.IDLE)
                    scheduleReconnect("انتهى البث")
            else -> Unit
        }
    }

    private fun markPlaying() {
        hasRendered = true
        attempt = 0
        lastFrameMs = SystemClock.elapsedRealtime()
        if (_status.value.state != RtspState.PLAYING) setStatus(RtspState.PLAYING, "")
    }

    private fun scheduleReconnect(reason: String) {
        try { player.stop() } catch (_: Exception) {}
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

    /** كل ثانية: يقرأ عدّاد الصور المعروضة من VLC (لحساب FPS وكشف التجمّد) ويراقب المهلات. */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                pollFrames(now)
                when (_status.value.state) {
                    RtspState.PLAYING ->
                        if (statsWorking && now - lastFrameMs > 6_000) scheduleReconnect("توقّف وصول الإطارات")
                    RtspState.CONNECTING, RtspState.BUFFERING ->
                        if (now - statusSince > 15_000) scheduleReconnect("انتهت مهلة الاتصال")
                    else -> Unit
                }
            }
        }
    }

    private fun pollFrames(now: Long) {
        val m = player.media ?: return
        try {
            val displayed = m.stats?.displayedPictures ?: return
            val delta = displayed - lastDisplayed
            if (delta > 0) {
                statsWorking = true
                frameCounter.addAndGet(delta)
                lastDisplayed = displayed
                lastFrameMs = now
                if (_status.value.state != RtspState.PLAYING) markPlaying()
            }
        } finally {
            m.release()
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

    /** يرمّز اسم المستخدم وكلمة المرور (إن احتويا رموزًا خاصة مثل @ # : /) قبل تمريرها. */
    private fun normalizeRtspUrl(raw: String): String {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return raw
        val scheme = raw.substring(0, schemeEnd + 3)
        val rest = raw.substring(schemeEnd + 3)
        val at = rest.lastIndexOf('@')
        if (at < 0) return raw
        val userInfo = rest.substring(0, at)
        val hostAndPath = rest.substring(at + 1)
        val colon = userInfo.indexOf(':')
        val user = if (colon < 0) userInfo else userInfo.substring(0, colon)
        val pass = if (colon < 0) null else userInfo.substring(colon + 1)
        fun enc(x: String) = if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(x)) x else Uri.encode(x)
        return scheme + enc(user) + (pass?.let { ":" + enc(it) } ?: "") + "@" + hostAndPath
    }
}
