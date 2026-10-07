package com.example.persontracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.example.persontracker.data.AppSettings
import com.example.persontracker.data.PerformanceProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(settings.rtspUrl) }
    var forceTcp by rememberSaveable { mutableStateOf(settings.forceTcp) }
    var autoReconnect by rememberSaveable { mutableStateOf(settings.autoReconnect) }
    var profileName by rememberSaveable { mutableStateOf(settings.profile.name) }
    var confidence by rememberSaveable { mutableStateOf(settings.minConfidence) }

    val trimmed = url.trim()
    val urlValid = (trimmed.startsWith("rtsp://", true) || trimmed.startsWith("rtsps://", true)) && trimmed.length > 10

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إعدادات الكاميرا") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("عنوان البث")
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("RTSP URL") },
                singleLine = false,
                minLines = 2,
                isError = !urlValid,
                supportingText = {
                    Text(
                        if (urlValid) "rtsp://المستخدم:كلمة_المرور@العنوان:المنفذ/المسار"
                        else "يجب أن يبدأ العنوان بـ rtsp://",
                    )
                },
                textStyle = TextStyle(textDirection = TextDirection.Ltr, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Text(
                "نصيحة: لأجهزة Hikvision استخدم ‎.../Channels/702‎ (بث فرعي) لتقليل استهلاك المعالج والبطارية بشكل كبير.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ToggleRow("RTP عبر TCP", "أكثر ثباتًا على Wi-Fi (موصى به). أوقفه لتأخير أقل في شبكة سلكية جيدة.", forceTcp) { forceTcp = it }
            ToggleRow("إعادة الاتصال تلقائيًا", "محاولات بتأخير تصاعدي عند انقطاع البث أو الشبكة.", autoReconnect) { autoReconnect = it }

            Section("الاكتشاف والأداء")
            PerformanceProfile.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { profileName = p.name },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = profileName == p.name, onClick = { profileName = p.name })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(p.label, fontWeight = FontWeight.SemiBold)
                        Text(p.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text("حدّ الثقة الأدنى: ${(confidence * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
            Slider(value = confidence, onValueChange = { confidence = it }, valueRange = 0.25f..0.85f)
            Text(
                "رفعه يقلل الكشوفات الخاطئة؛ خفضه يلتقط أشخاصًا أبعد أو أصغر.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val d = AppSettings()
                        url = d.rtspUrl; forceTcp = d.forceTcp; autoReconnect = d.autoReconnect
                        profileName = d.profile.name; confidence = d.minConfidence
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("الافتراضي") }
                Button(
                    enabled = urlValid,
                    onClick = {
                        onSave(
                            settings.copy(
                                rtspUrl = trimmed,
                                forceTcp = forceTcp,
                                autoReconnect = autoReconnect,
                                profile = PerformanceProfile.entries.firstOrNull { it.name == profileName }
                                    ?: PerformanceProfile.BALANCED,
                                minConfidence = confidence,
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("حفظ وتطبيق") }
            }

            Text(
                "الخصوصية: تُعالَج الفيديوهات على هذا الهاتف فقط، ولا يوجد أي خادم. التطبيق يكتشف وجود أشخاص ويتتبع حركتهم " +
                    "ولا يقوم بالتعرف على الوجوه أو تحديد الهوية.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
