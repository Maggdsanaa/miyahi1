package com.example.persontracker.detection

import android.graphics.Bitmap
import com.example.persontracker.domain.BoxF
import com.example.persontracker.domain.Detection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * يرفع دقة اكتشاف الأشخاص الصغار/البعيدين: بدل تصغير الإطار كاملًا إلى 320×320 (تشويه + فقدان تفاصيل)،
 * يقسمه إلى مربعات متراكبة (ضلع المربع = الضلع القصير للصورة) ويشغّل النموذج على كلٍّ منها،
 * ثم يدمج النتائج بـ NMS. كل مربع يُصغَّر أقل (~1.8× بدل ~3×) وبلا تشويه في النسبة.
 * الكلفة: 2× زمن الاستدلال لصورة 16:9.
 */
class TiledPersonDetector(
    private val inner: PersonDetector,
    private val maxOverlapStep: Float = 0.7f, // أقصى خطوة بين مربعين كنسبة من ضلع المربع (تراكب ≥ 30%)
) : PersonDetector {

    @Volatile override var lastMaxScore: Float = 0f
        private set

    override fun detect(bitmap: Bitmap): List<Detection> {
        val w = bitmap.width
        val h = bitmap.height
        val side = min(w, h)
        val long = max(w, h)
        val n = if (long <= side) 1 else ceil((long - side) / (maxOverlapStep * side)).toInt() + 1
        val step = if (n > 1) (long - side).toFloat() / (n - 1) else 0f

        val all = ArrayList<Detection>()
        var maxScore = 0f
        for (i in 0 until n) {
            val off = (i * step).toInt()
            val x = if (w >= h) off else 0
            val y = if (w >= h) 0 else off
            val tile = Bitmap.createBitmap(bitmap, x, y, side, side)
            try {
                for (d in inner.detect(tile)) {
                    // تحويل إحداثيات المربع الطبيعية إلى إحداثيات الصورة الكاملة الطبيعية
                    all.add(
                        Detection(
                            BoxF(
                                (x + d.box.l * side) / w, (y + d.box.t * side) / h,
                                (x + d.box.r * side) / w, (y + d.box.b * side) / h,
                            ),
                            d.score,
                        )
                    )
                }
                maxScore = max(maxScore, inner.lastMaxScore)
            } finally {
                if (tile !== bitmap) tile.recycle()
            }
        }
        lastMaxScore = maxScore
        return nms(all)
    }

    private fun nms(dets: List<Detection>): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in sorted) {
            val dup = kept.any { k ->
                val iw = min(k.box.r, d.box.r) - max(k.box.l, d.box.l)
                val ih = min(k.box.b, d.box.b) - max(k.box.t, d.box.t)
                val inter = if (iw > 0 && ih > 0) iw * ih else 0f
                val minArea = min(k.box.area, d.box.area)
                k.box.iou(d.box) > 0.5f || (minArea > 0f && inter / minArea > 0.8f)
            }
            if (!dup) kept.add(d)
        }
        return kept
    }

    override fun close() = inner.close()
}
