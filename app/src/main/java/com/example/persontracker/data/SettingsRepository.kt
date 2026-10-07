package com.example.persontracker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.persontracker.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** ملفات تعريف الأداء: دقة إدخال الاكتشاف والفاصل الزمني بين عمليتي استدلال. */
enum class PerformanceProfile(
    val label: String,
    val description: String,
    val inputLongSide: Int,
    val intervalMs: Long,
) {
    ECO("اقتصادي", "256px · حتى ~4 اكتشافات/ث · أقل استهلاك للبطارية", 256, 250),
    BALANCED("متوازن", "320px · حتى ~8 اكتشافات/ث · الخيار الموصى به", 320, 120),
    PERFORMANCE("أداء عالٍ", "448px · حتى ~15 اكتشاف/ث · للأجهزة القوية", 448, 60),
}

data class AppSettings(
    val rtspUrl: String = BuildConfig.DEFAULT_RTSP_URL,
    val detectionEnabled: Boolean = true,
    val profile: PerformanceProfile = PerformanceProfile.BALANCED,
    val forceTcp: Boolean = true,
    val autoReconnect: Boolean = true,
    val minConfidence: Float = 0.35f,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("rtsp_url")
        val DETECTION = booleanPreferencesKey("detection_enabled")
        val PROFILE = stringPreferencesKey("profile")
        val TCP = booleanPreferencesKey("force_tcp")
        val RECONNECT = booleanPreferencesKey("auto_reconnect")
        val CONFIDENCE = floatPreferencesKey("min_confidence")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                rtspUrl = p[Keys.URL] ?: d.rtspUrl,
                detectionEnabled = p[Keys.DETECTION] ?: d.detectionEnabled,
                profile = p[Keys.PROFILE]
                    ?.let { n -> PerformanceProfile.entries.firstOrNull { it.name == n } }
                    ?: d.profile,
                forceTcp = p[Keys.TCP] ?: d.forceTcp,
                autoReconnect = p[Keys.RECONNECT] ?: d.autoReconnect,
                minConfidence = p[Keys.CONFIDENCE] ?: d.minConfidence,
            )
        }

    suspend fun save(s: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.URL] = s.rtspUrl.trim()
            p[Keys.DETECTION] = s.detectionEnabled
            p[Keys.PROFILE] = s.profile.name
            p[Keys.TCP] = s.forceTcp
            p[Keys.RECONNECT] = s.autoReconnect
            p[Keys.CONFIDENCE] = s.minConfidence
        }
    }
}
