package com.example.persontracker.detection

import android.content.Context
import android.graphics.Bitmap
import com.example.persontracker.domain.BoxF
import com.example.persontracker.domain.Detection
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * اكتشاف الأشخاص محليًا بنموذج EfficientDet-Lite0 (int8) عبر MediaPipe Tasks (TFLite + XNNPACK على CPU).
 * النموذج صغير (~4MB) ويعمل بسلاسة على الأجهزة الضعيفة. يُرشَّح على فئة "person" فقط.
 */
class MediaPipePersonDetector(
    context: Context,
    modelAsset: String = "person_detector.tflite",
    scoreThreshold: Float = 0.12f,
) : PersonDetector {

    private val detector: ObjectDetector

    @Volatile override var lastMaxScore: Float = 0f
        private set

    init {
        val base = BaseOptions.builder().setModelAssetPath(modelAsset).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(scoreThreshold)
            .setMaxResults(25)
            .setCategoryAllowlist(listOf("person"))
            .build()
        detector = ObjectDetector.createFromOptions(context.applicationContext, options)
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val out = ArrayList<Detection>()
        var maxScore = 0f
        for (d in result.detections()) {
            val c = d.categories().firstOrNull() ?: continue
            if (c.categoryName() != "person") continue
            if (c.score() > maxScore) maxScore = c.score()
            val b = d.boundingBox()
            val box = BoxF(
                (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f),
            )
            if (box.w > 0.005f && box.h > 0.005f) out.add(Detection(box, c.score()))
        }
        lastMaxScore = maxScore
        return out
    }

    override fun close() {
        detector.close()
    }
}
