package com.example.persontracker.tracking

import com.example.persontracker.domain.BoxF
import com.example.persontracker.domain.Detection
import com.example.persontracker.domain.TrackedPerson
import kotlin.math.max

/** إعدادات المتتبّع. الحقول قابلة للتغيير أثناء التشغيل. */
class TrackerConfig(
    /** عتبة الثقة العالية (المرحلة الأولى من ByteTrack + إنشاء مسارات جديدة). */
    @Volatile var highThreshold: Float = 0.45f,
    /** أدنى ثقة للكشوفات «المنخفضة» التي تُستخدم لإنقاذ المسارات في المرحلة الثانية. */
    @Volatile var lowThreshold: Float = 0.20f,
    @Volatile var matchIou: Float = 0.20f,
    @Volatile var lowMatchIou: Float = 0.40f,
    @Volatile var tentativeIou: Float = 0.30f,
    /** عدد الإطارات المطابِقة قبل منح المسار رقمًا ظاهرًا (يمنع الوميض والكشف الخاطئ). */
    @Volatile var minHits: Int = 2,
    /** المدة التي يُحتفظ فيها بمسار ضائع لاستعادة نفس الرقم عند عودة الشخص. */
    @Volatile var maxLostMs: Long = 2000,
    /** المدة التي يُرسَم فيها صندوق متنبَّأ به بعد فقدان الكشف مباشرة. */
    @Volatile var showLostMs: Long = 500,
)

/** مرشّح كالمان أحادي البعد (موضع + سرعة) بنموذج سرعة ثابتة. خفيف ولا يحتاج مصفوفات. */
internal class Kalman1D(x0: Float, private val accel: Float, r0: Float, v0Var: Float) {
    var x = x0
        private set
    var v = 0f
        private set
    private var p00 = r0
    private var p01 = 0f
    private var p11 = v0Var

    fun predict(dt: Float) {
        x += v * dt
        val a2 = accel * accel
        val dt2 = dt * dt
        p00 += 2f * dt * p01 + dt2 * p11 + 0.25f * dt2 * dt2 * a2
        p01 += dt * p11 + 0.5f * dt2 * dt * a2
        p11 += dt2 * a2
    }

    fun update(z: Float, r: Float) {
        val s = p00 + r
        val k0 = p00 / s
        val k1 = p01 / s
        val y = z - x
        x += k0 * y
        v += k1 * y
        val n00 = (1f - k0) * p00
        val n01 = (1f - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
    }

    fun damp(f: Float) {
        v *= f
    }
}

internal class Track(det: Detection, nowMs: Long) {
    private val kcx: Kalman1D
    private val kcy: Kalman1D
    private val kw: Kalman1D
    private val kh: Kalman1D

    var score = det.score
    var hits = 1
    var lastUpdateMs = nowMs
    var lost = false
    /** 0 = مسار مبدئي لم يُؤكَّد بعد. */
    var displayId = 0

    init {
        val b = det.box
        val s = max(b.h, 0.05f)
        val rp = sq(POS_STD * s)
        val rs = sq(SIZE_STD * s)
        kcx = Kalman1D(b.cx, 1.0f, rp, 0.5f)
        kcy = Kalman1D(b.cy, 1.0f, rp, 0.5f)
        kw = Kalman1D(b.w, 0.15f, rs, 0.05f)
        kh = Kalman1D(b.h, 0.15f, rs, 0.05f)
    }

    fun predict(dt: Float) {
        if (lost) {
            // أثناء الفقدان نُخمِّد السرعة حتى لا ينجرف الصندوق المتنبَّأ به بعيدًا
            kcx.damp(0.85f); kcy.damp(0.85f)
        }
        kcx.predict(dt); kcy.predict(dt); kw.predict(dt); kh.predict(dt)
    }

    fun update(det: Detection, nowMs: Long) {
        val b = det.box
        val s = max(b.h, 0.05f)
        val rp = sq(POS_STD * s)
        val rs = sq(SIZE_STD * s)
        kcx.update(b.cx, rp); kcy.update(b.cy, rp)
        kw.update(b.w, rs); kh.update(b.h, rs)
        hits++
        lastUpdateMs = nowMs
        lost = false
        score = det.score
    }

    fun box(): BoxF = BoxF.fromCenter(kcx.x, kcy.x, max(kw.x, 0.005f), max(kh.x, 0.005f))

    private companion object {
        const val POS_STD = 0.05f
        const val SIZE_STD = 0.08f
        fun sq(x: Float) = x * x
    }
}

/**
 * تنفيذ خفيف لخوارزمية ByteTrack (Zhang et al., 2022):
 *  1) ربط المسارات المؤكدة بالكشوفات عالية الثقة (IoU + هنغارية).
 *  2) ربط المسارات المتبقية بالكشوفات منخفضة الثقة لإنقاذها أثناء الحجب/التعثر.
 *  3) ربط المسارات المبدئية بما تبقّى من كشوفات عالية الثقة وإنشاء مسارات جديدة.
 * لا يستخدم أي ميزات مظهرية (Re-ID) ولا تعرّفًا على الوجوه: الحركة والمواضع فقط.
 *
 * ليست آمنة للاستخدام المتزامن من عدة خيوط؛ استدعِها من خيط واحد.
 */
class ByteTracker(val config: TrackerConfig = TrackerConfig()) {

    private val tracks = ArrayList<Track>()
    private var nextDisplayId = 1
    private var lastMs = -1L

    fun reset() {
        tracks.clear()
        nextDisplayId = 1
        lastMs = -1L
    }

    fun update(detections: List<Detection>, nowMs: Long): List<TrackedPerson> {
        val dt = if (lastMs < 0) 0.1f else ((nowMs - lastMs) / 1000f).coerceIn(0.01f, 1f)
        lastMs = nowMs
        tracks.forEach { it.predict(dt) }

        val high = detections.filter { it.score >= config.highThreshold }
        val low = detections.filter { it.score >= config.lowThreshold && it.score < config.highThreshold }

        val confirmed = tracks.filter { it.displayId != 0 }
        val tentative = tracks.filter { it.displayId == 0 }

        // 1) مسارات مؤكدة × كشوفات عالية
        val r1 = associate(confirmed, high, config.matchIou)
        for ((ti, di) in r1.matches) confirmed[ti].update(high[di], nowMs)
        val leftHigh = r1.unmatchedDets.map { high[it] }

        // 2) مسارات لم تضِع في الإطار السابق × كشوفات منخفضة
        val remainTracked = r1.unmatchedTracks.map { confirmed[it] }.filter { !it.lost }
        val r2 = associate(remainTracked, low, config.lowMatchIou)
        for ((ti, di) in r2.matches) remainTracked[ti].update(low[di], nowMs)

        // ما لم يُحدَّث في هذا الإطار يصبح «ضائعًا»
        for (t in confirmed) if (t.lastUpdateMs != nowMs) t.lost = true

        // 3) مسارات مبدئية × ما تبقى من كشوفات عالية
        val r3 = associate(tentative, leftHigh, config.tentativeIou)
        for ((ti, di) in r3.matches) {
            val t = tentative[ti]
            t.update(leftHigh[di], nowMs)
            if (t.hits >= config.minHits) t.displayId = nextDisplayId++
        }
        val dropped = r3.unmatchedTracks.map { tentative[it] }.toSet()
        tracks.removeAll(dropped)

        // مسارات جديدة
        for (di in r3.unmatchedDets) {
            val t = Track(leftHigh[di], nowMs)
            if (config.minHits <= 1) t.displayId = nextDisplayId++
            tracks.add(t)
        }

        tracks.removeAll { nowMs - it.lastUpdateMs > config.maxLostMs }

        return tracks
            .filter { it.displayId != 0 && (!it.lost || nowMs - it.lastUpdateMs <= config.showLostMs) }
            .sortedBy { it.displayId }
            .map { TrackedPerson(it.displayId, it.box(), it.score) }
    }

    private class Assignment(
        val matches: List<Pair<Int, Int>>,
        val unmatchedTracks: List<Int>,
        val unmatchedDets: List<Int>,
    )

    private fun associate(tr: List<Track>, dets: List<Detection>, minIou: Float): Assignment {
        if (tr.isEmpty() || dets.isEmpty()) {
            return Assignment(emptyList(), tr.indices.toList(), dets.indices.toList())
        }
        val cost = Array(tr.size) { i ->
            val tb = tr[i].box()
            FloatArray(dets.size) { j -> 1f - tb.iou(dets[j].box) }
        }
        val rowToCol = Hungarian.solve(cost)
        val matches = ArrayList<Pair<Int, Int>>()
        val usedDets = BooleanArray(dets.size)
        val unmatchedTracks = ArrayList<Int>()
        for (i in tr.indices) {
            val j = rowToCol[i]
            if (j >= 0 && 1f - cost[i][j] >= minIou) {
                matches.add(i to j)
                usedDets[j] = true
            } else {
                unmatchedTracks.add(i)
            }
        }
        val unmatchedDets = dets.indices.filter { !usedDets[it] }
        return Assignment(matches, unmatchedTracks, unmatchedDets)
    }
}
