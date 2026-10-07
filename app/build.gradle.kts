import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- عنوان RTSP الافتراضي: من local.properties (حتى لا تُرفع كلمة المرور إلى GitHub) ----
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultRtsp: String = (localProps.getProperty("rtsp.default.url")?.takeIf { it.isNotBlank() }
    ?: System.getenv("RTSP_DEFAULT_URL")?.takeIf { it.isNotBlank() }
    ?: "rtsp://admin:PASSWORD@192.168.1.100:554/Streaming/Channels/701")
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.persontracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.persontracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "DEFAULT_RTSP_URL", "\"$defaultRtsp\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // اضبطها true بعد اختبار قواعد R8 (انظر proguard-rules.pro)
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // توقيع debug ليصبح APK قابلًا للتثبيت مباشرة. استبدله بمفتاحك قبل النشر.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += "tflite" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    lint { abortOnError = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Media3 / RTSP
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // اكتشاف الأشخاص محليًا (TFLite + XNNPACK)
    implementation("com.google.mediapipe:tasks-vision:0.10.21")

    testImplementation("junit:junit:4.13.2")
}

// ---- تنزيل نموذج الاكتشاف (EfficientDet-Lite0 int8, ~4.4MB) تلقائيًا قبل البناء ----
val modelFile = layout.projectDirectory.file("src/main/assets/person_detector.tflite").asFile
val downloadPersonModel by tasks.registering {
    description = "Downloads the on-device person detection model if it is missing."
    outputs.file(modelFile)
    onlyIf { !modelFile.exists() || modelFile.length() < 100_000L }
    doLast {
        val urls = listOf(
            "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite",
            "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float32/1/efficientdet_lite0.tflite",
        )
        modelFile.parentFile.mkdirs()
        var ok = false
        for (u in urls) {
            try {
                java.net.URL(u).openStream().use { input ->
                    modelFile.outputStream().use { out -> input.copyTo(out) }
                }
                ok = modelFile.length() > 100_000L
                if (ok) break
            } catch (e: Exception) {
                logger.warn("Model download failed from $u: ${e.message}")
            }
        }
        if (!ok) throw GradleException(
            "تعذّر تنزيل النموذج. نزّله يدويًا وضعه في app/src/main/assets/person_detector.tflite (انظر README)."
        )
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadPersonModel) }
