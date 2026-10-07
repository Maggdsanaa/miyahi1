# RTSP Person Tracker — تطبيق Android لاكتشاف وتتبع الأشخاص من كاميرا IP

تطبيق **Kotlin + Jetpack Compose** يعرض بثّ **RTSP** مباشرة من الكاميرا عبر **libVLC**، ويكتشف الأشخاص ويتتبّعهم **محليًا على الهاتف** (بدون خادم وبدون إنترنت)،
ويرسم صندوقًا ومعرّفًا ثابتًا لكل شخص (`Person 1`, `Person 2`, …) مع عدد الأشخاص الحاليين.

> **الخصوصية:** لا يوجد أي تعرّف على الوجوه أو تحديد هوية. التطبيق يكتشف *وجود شخص* ويتتبع *حركته* داخل الفيديو فقط
> (ByteTrack يعتمد على الموضع/الحركة، لا على المظهر). المعرّفات مؤقتة وتزول بزوال الشخص.

## المعمارية

```mermaid
flowchart LR
    CAM[كاميرا IP<br/>RTSP] -->|RTP/TCP| EXO[libVLC<br/>فكّ ترميز عتادي]
    EXO --> TV[TextureView]
    TV -->|عرض مباشر بدون تأخير إضافي| SCREEN[الشاشة]
    TV -->|getBitmap صغير<br/>256–448px كل 60–250ms| DET[PersonDetector<br/>EfficientDet-Lite0 int8<br/>TFLite + XNNPACK]
    DET -->|Detections طبيعية 0..1| TRK[ByteTracker<br/>Kalman + Hungarian]
    TRK -->|Person N + Box| OVL[Compose Overlay]
    OVL --> SCREEN
    DS[(DataStore<br/>الإعدادات)] --> VM[MainViewModel]
    VM --> EXO
    VM --> DET
```

**قرارات التصميم الأساسية**

| القرار | السبب |
|---|---|
| الفيديو المعروض **لا يمرّ** عبر الاكتشاف | البث يبقى بأقل تأخير حتى لو كان الاستدلال بطيئًا؛ الصناديق فقط تتأخر قليلًا |
| فكّ ترميز عتادي (MediaCodec) + التقاط إطار صغير من `TextureView` | لا نفكّ ترميز الفيديو مرتين، ولا نحوّل YUV→RGB بدقة كاملة |
| إحداثيات طبيعية (0..1) | الاكتشاف والتتبع والرسم مستقلة عن دقة الفيديو |
| ByteTrack (مرحلتان: عالية/منخفضة الثقة) + Kalman | ثبات المعرّف أثناء الحجب أو تذبذب الثقة، وبدون Re-ID/وجوه |
| لا بثّ ولا معالجة في الخلفية | توفير البطارية (`ON_STOP` يوقف كل شيء) |
| تخفيض تلقائي للمعدل عند الحرارة العالية أو وضع توفير الطاقة | حماية الأجهزة الضعيفة |
| `PersonDetector` واجهة | يمكن استبدال النموذج بـ YOLO دون لمس بقية الكود |

## هيكل المشروع

```
app/src/main/java/com/example/persontracker/
├── MainActivity.kt
├── data/SettingsRepository.kt        # DataStore + ملفات تعريف الأداء
├── domain/Models.kt                  # BoxF, Detection, TrackedPerson, RtspStatus…
├── detection/
│   ├── PersonDetector.kt             # الواجهة
│   └── MediaPipePersonDetector.kt    # EfficientDet-Lite0 int8 عبر MediaPipe Tasks
├── tracking/
│   ├── ByteTracker.kt                # ByteTrack + Kalman أحادي البعد
│   └── Hungarian.kt                  # خوارزمية الإسناد
├── player/RtspController.kt          # libVLC + إعادة الاتصال + مراقب التجمّد + مراقب الشبكة
└── ui/                               # Compose: LiveScreen, SettingsScreen, Overlay, ViewModel
```

## التشغيل

1. افتح المجلد في **Android Studio** (Ladybug أو أحدث، JDK 17) واتركه يزامن Gradle.
2. أول بناء يُنزّل نموذج الاكتشاف (~4MB) تلقائيًا إلى `app/src/main/assets/person_detector.tflite`
   (المهمة `downloadPersonModel`). عند فشل التنزيل نزّله يدويًا من:
   `https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite`
3. **عنوان الكاميرا:** من شاشة *الإعدادات* داخل التطبيق، أو مسبقًا عبر `local.properties` (غير مرفوع إلى Git):
   ```properties
   rtsp.default.url=rtsp://admin:YOUR_PASSWORD@192.168.1.100:554/Streaming/Channels/701
   ```
4. شغّل على هاتف حقيقي متصل بنفس شبكة الكاميرا.

> لا تضع كلمة مرور الكاميرا في الكود أو في ملفات مرفوعة إلى GitHub. هذا هو سبب قراءة العنوان الافتراضي من `local.properties`.

## البناء من سطر الأوامر و GitHub Actions

```bash
./gradlew testDebugUnitTest assembleDebug     # app/build/outputs/apk/debug/
./gradlew assembleRelease                      # موقّع بمفتاح debug افتراضيًا
```

* ملف `.github/workflows/android.yml` يشغّل الاختبارات ويبني APK (debug + release) عند كل `push`/`pull_request` ويرفعها كـ **Artifacts**.
* لإصدار رسمي: `git tag v1.0.0 && git push --tags` ينشئ **GitHub Release** يحتوي على ملفات APK.
* للنشر الحقيقي استبدل `signingConfig` في `app/build.gradle.kts` بمفتاحك الخاص (خزّنه في GitHub Secrets).

## نصائح الأداء (مهمة للأجهزة الضعيفة)

* **استخدم البث الفرعي** للكاميرا: في Hikvision `Channels/702` بدل `701` (دقة أقل ← فكّ ترميز أخفّ ← بطارية أقل). الاكتشاف يعمل أصلًا على 256–448px فلا حاجة لبث 4K.
* اختر **اقتصادي** من الإعدادات لأقل استهلاك، أو أوقف الاكتشاف تمامًا عند الحاجة للمشاهدة فقط.
* أفضل ترميز هو **H.264**. H.265 يعمل فقط إن كان الهاتف يدعمه عتاديًا؛ وإلا يظهر خطأ «تعذّر فكّ الترميز».
* وضع **RTP عبر TCP** أكثر ثباتًا على Wi-Fi؛ أوقفه فقط إن أردت تأخيرًا أقل على شبكة جيدة.

## استبدال النموذج بـ YOLO

نفّذ الواجهة `PersonDetector` (مثلًا YOLOv8n/YOLO11n بصيغة TFLite عبر `org.tensorflow:tensorflow-lite` + XNNPACK)،
وأعد `Detection(BoxF(l,t,r,b) طبيعي, score)` لفئة `person` فقط بعد NMS، ثم استبدل سطر الإنشاء في
`MainViewModel.detectionLoop()`. التتبع والواجهة لا يحتاجان أي تغيير.

## استكشاف الأخطاء

| العَرَض | الحل |
|---|---|
| «تعذّر الوصول إلى الكاميرا» | تأكد أن الهاتف والكاميرا على نفس الشبكة (وليس شبكة ضيوف معزولة)، وجرّب الرابط في VLC |
| 401 / فشل المصادقة | راجع المستخدم وكلمة المرور؛ الأحرف الخاصة في كلمة المرور يجب ترميزها (`@` ← `%40`) |
| فيديو بلا صناديق | تأكد أن مفتاح الاكتشاف مفعّل وأن النموذج موجود في `assets/` |
| «تعذّر فكّ ترميز الفيديو» | غيّر ترميز الكاميرا إلى H.264 أو استخدم البث الفرعي |
| ثقيل/يسخّن الهاتف | ملف «اقتصادي» + بث فرعي |

## ملاحظات

* الصلاحية `INTERNET` مطلوبة تقنيًا لفتح socket إلى الكاميرا، لكن لا يلزم اتصال بالإنترنت إطلاقًا.
* الإعدادات (بما فيها عنوان RTSP) محفوظة محليًا في DataStore بنصّ عادي داخل مساحة التطبيق الخاصة (`allowBackup=false`).
* الاختبارات: `tracking/TrackingTest.kt` تغطي الهنغارية وثبات المعرّفات والفجوات القصيرة/الطويلة والكشف الخاطئ.
