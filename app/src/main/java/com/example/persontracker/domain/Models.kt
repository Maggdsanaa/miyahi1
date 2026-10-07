package com.example.persontracker.domain

import kotlin.math.max
import kotlin.math.min

/** صندوق بإحداثيات طبيعية (0..1) نسبةً إلى الإطار، فيكون مستقلًّا عن دقة الفيديو. */
data class BoxF(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) * 0.5f
    val cy: Float get() = (t + b) * 0.5f
    val area: Float get() = max(0f, w) * max(0f, h)

    fun iou(o: BoxF): Float {
        val iw = min(r, o.r) - max(l, o.l)
        val ih = min(b, o.b) - max(t, o.t)
        if (iw <= 0f || ih <= 0f) return 0f
        val inter = iw * ih
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        fun fromCenter(cx: Float, cy: Float, w: Float, h: Float): BoxF {
            val hw = w * 0.5f
            val hh = h * 0.5f
            return BoxF(
                (cx - hw).coerceIn(0f, 1f), (cy - hh).coerceIn(0f, 1f),
                (cx + hw).coerceIn(0f, 1f), (cy + hh).coerceIn(0f, 1f),
            )
        }
    }
}

data class Detection(val box: BoxF, val score: Float)

/** شخص متتبَّع: [id] ثابت ما دام الشخص ظاهرًا (Person 1, Person 2, ...). */
data class TrackedPerson(val id: Int, val box: BoxF, val score: Float)

enum class RtspState { IDLE, CONNECTING, PLAYING, BUFFERING, RECONNECTING, ERROR }

data class RtspStatus(
    val state: RtspState = RtspState.IDLE,
    val message: String = "",
    val attempt: Int = 0,
)

data class PipelineStats(
    val videoFps: Float = 0f,
    val detectFps: Float = 0f,
    val inferenceMs: Long = 0L,
)

/** يُخفي كلمة المرور من عنوان RTSP قبل عرضه في الواجهة. */
fun String.maskedRtsp(): String =
    replace(Regex("^(rtsp[s]?://)([^:@/]+):([^@/]*)@", RegexOption.IGNORE_CASE), "$1$2:••••@")
