package com.example.persontracker.detection

import android.graphics.Bitmap
import com.example.persontracker.domain.Detection

/**
 * واجهة اكتشاف الأشخاص. لاستبدال النموذج بـ YOLO (مثلًا YOLOv8n/YOLO11n بصيغة TFLite)
 * يكفي تنفيذ هذه الواجهة وتمريرها في MainViewModel؛ باقي المسار (التتبع/الواجهة) لا يتغير.
 */
interface PersonDetector : AutoCloseable {
    /** يعيد الأشخاص فقط، بإحداثيات طبيعية 0..1. يُستدعى من خيط خلفي واحد. */
    fun detect(bitmap: Bitmap): List<Detection>
}
