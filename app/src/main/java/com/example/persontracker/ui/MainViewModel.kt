package com.example.persontracker.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.persontracker.data.AppSettings
import com.example.persontracker.data.SettingsRepository
import com.example.persontracker.detection.MediaPipePersonDetector
import com.example.persontracker.detection.PersonDetector
import com.example.persontracker.domain.PipelineStats
import com.example.persontracker.domain.RtspState
import com.example.persontracker.domain.TrackedPerson
import com.example.persontracker.player.RtspController
import com.example.persontracker.tracking.ByteTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * المعمارية:
 *   RTSP ──► libVLC (فكّ ترميز عتادي) ──► TextureView ──► (عرض مباشر على الشاشة)
 *                                                │
 *                          getBitmap() بدقة صغيرة كل N ms
 *                                                ▼
 *                     PersonDetector (TFLite) ──► ByteTracker ──► Overlay (Compose)
 * الفيديو المعروض لا يمرّ عبر الاكتشاف أبدًا، فلا يتأخر البث بسبب بطء الاستدلال.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    val controller = RtspController(app, viewModelScope)
    private val tracker = ByteTracker()

    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    private val _people = MutableStateFlow<List<TrackedPerson>>(emptyList())
    val people: StateFlow<List<TrackedPerson>> = _people.asStateFlow()

    private val _stats = MutableStateFlow(PipelineStats())
    val stats: StateFlow<PipelineStats> = _stats.asStateFlow()

    private val _detectorError = MutableStateFlow<String?>(null)
    val detectorError: StateFlow<String?> = _detectorError.asStateFlow()

    /** تسجّله الواجهة: ينسخ إطار الفيديو الحالي إلى Bitmap (يُنفَّذ على الخيط الرئيسي). */
    @Volatile var frameGrabber: ((Bitmap) -> Boolean)? = null

    @Volatile private var foreground = false
    private val resetRequested = AtomicBoolean(false)
    private var workBitmap: Bitmap? = null
    private var detectJob: Job? = null
    private var statsJob: Job? = null

    init {
        viewModelScope.launch {
            repo.settings.collect { s ->
                val prev = _settings.value
                _settings.value = s
                tracker.config.highThreshold = s.minConfidence
                controller.autoReconnect = s.autoReconnect
                val connectionChanged = prev == null || prev.rtspUrl != s.rtspUrl || prev.forceTcp != s.forceTcp
                if (foreground && connectionChanged) startStream(s)
                if (prev != null && prev.detectionEnabled != s.detectionEnabled) resetRequested.set(true)
            }
        }
        controller.startNetworkMonitor()
        detectJob = viewModelScope.launch(Dispatchers.Default) { detectionLoop() }
        statsJob = viewModelScope.launch { statsLoop() }
    }

    // ---- دورة الحياة: لا نبثّ ولا نعالج في الخلفية (توفير البطارية) ----
    fun onForeground() {
        foreground = true
        _settings.value?.let { startStream(it) }
    }

    fun onBackground() {
        foreground = false
        controller.stop()
        resetRequested.set(true)
    }

    // ---- أوامر الواجهة ----
    fun saveSettings(s: AppSettings) {
        viewModelScope.launch { repo.save(s) }
    }

    fun setDetectionEnabled(enabled: Boolean) {
        _settings.value?.let { saveSettings(it.copy(detectionEnabled = enabled)) }
    }

    fun reconnectNow() {
        resetRequested.set(true)
        controller.reconnectNow()
    }

    private fun startStream(s: AppSettings) {
        resetRequested.set(true)
        controller.start(s.rtspUrl, s.forceTcp, s.autoReconnect)
    }

    // ---- حلقة الاكتشاف + التتبع ----
    private suspend fun detectionLoop() {
        var detector: PersonDetector? = null
        val pm = getApplication<Application>().getSystemService(PowerManager::class.java)
        var lastDetectMs = 0L
        var emaFps = 0f
        try {
            while (currentCoroutineContext().isActive) {
                if (resetRequested.getAndSet(false)) {
                    tracker.reset()
                    _people.value = emptyList()
                }
                val s = _settings.value
                val active = s != null && s.detectionEnabled && foreground &&
                    controller.status.value.state == RtspState.PLAYING
                if (s == null || !active) {
                    if (_people.value.isNotEmpty()) {
                        tracker.reset()
                        _people.value = emptyList()
                    }
                    _stats.update { it.copy(detectFps = 0f, inferenceMs = 0L) }
                    emaFps = 0f
                    delay(250)
                    continue
                }

                if (detector == null) {
                    detector = try {
                        MediaPipePersonDetector(getApplication()).also { _detectorError.value = null }
                    } catch (t: Throwable) {
                        _detectorError.value = "تعذّر تحميل نموذج الاكتشاف: ${t.message ?: t.javaClass.simpleName}"
                        null
                    }
                    if (detector == null) {
                        delay(3_000)
                        continue
                    }
                }

                val t0 = SystemClock.elapsedRealtime()
                val target = obtainBitmap(controller.videoAspect.value, s.profile.inputLongSide)
                val grabbed = withContext(Dispatchers.Main.immediate) {
                    frameGrabber?.invoke(target) ?: false
                }
                if (!grabbed) {
                    delay(100)
                    continue
                }

                val tInfer = SystemClock.elapsedRealtime()
                val detections = detector.detect(target)
                val inferMs = SystemClock.elapsedRealtime() - tInfer

                val now = SystemClock.elapsedRealtime()
                _people.value = tracker.update(detections, now)

                if (lastDetectMs != 0L) {
                    val inst = 1000f / max(1L, now - lastDetectMs)
                    emaFps = if (emaFps == 0f) inst else emaFps * 0.8f + inst * 0.2f
                }
                lastDetectMs = now
                val luma = averageLuma(target)
                _stats.update {
                    it.copy(detectFps = emaFps, inferenceMs = inferMs, maxScore = detector.lastMaxScore, frameLuma = luma)
                }

                // خفض المعدل تلقائيًا عند الحرارة العالية أو وضع توفير الطاقة
                val thermal = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else 0
                val factor = when {
                    thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 3
                    thermal >= PowerManager.THERMAL_STATUS_MODERATE || pm.isPowerSaveMode -> 2
                    else -> 1
                }
                val wait = s.profile.intervalMs * factor - (SystemClock.elapsedRealtime() - t0)
                delay(max(wait, 4L))
            }
        } finally {
            detector?.close()
            workBitmap?.recycle()
            workBitmap = null
        }
    }

    /** متوسط سطوع الإطار من شبكة 16×9 عيّنة؛ 0 تعني إطارًا أسود (أي أن التقاط الصورة فاشل). */
    private fun averageLuma(b: Bitmap): Int {
        var sum = 0L
        var n = 0
        for (iy in 0 until 9) for (ix in 0 until 16) {
            val px = b.getPixel(((ix + 0.5f) / 16f * b.width).toInt().coerceIn(0, b.width - 1),
                ((iy + 0.5f) / 9f * b.height).toInt().coerceIn(0, b.height - 1))
            sum += (((px shr 16) and 0xFF) * 299 + ((px shr 8) and 0xFF) * 587 + (px and 0xFF) * 114) / 1000
            n++
        }
        return (sum / n).toInt()
    }

    private fun obtainBitmap(aspect: Float, longSide: Int): Bitmap {
        val w: Int
        val h: Int
        if (aspect >= 1f) {
            w = longSide
            h = max(32, (longSide / aspect).roundToInt())
        } else {
            h = longSide
            w = max(32, (longSide * aspect).roundToInt())
        }
        val cur = workBitmap
        if (cur != null && cur.width == w && cur.height == h) return cur
        cur?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { workBitmap = it }
    }

    private suspend fun statsLoop() {
        var last = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            val frames = controller.frameCounter.getAndSet(0)
            val fps = frames * 1000f / max(1L, now - last)
            last = now
            _stats.update { it.copy(videoFps = fps) }
        }
    }

    override fun onCleared() {
        detectJob?.cancel()
        statsJob?.cancel()
        controller.release()
        super.onCleared()
    }
}
