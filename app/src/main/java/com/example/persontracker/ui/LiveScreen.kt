package com.example.persontracker.ui

import android.content.res.Configuration
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.persontracker.data.AppSettings
import com.example.persontracker.domain.PipelineStats
import com.example.persontracker.domain.RtspState
import com.example.persontracker.domain.RtspStatus
import com.example.persontracker.domain.TrackedPerson
import com.example.persontracker.domain.maskedRtsp

fun stateLabel(s: RtspState) = when (s) {
    RtspState.IDLE -> "متوقف"
    RtspState.CONNECTING -> "جارٍ الاتصال"
    RtspState.PLAYING -> "متصل · مباشر"
    RtspState.BUFFERING -> "تخزين مؤقت"
    RtspState.RECONNECTING -> "إعادة الاتصال"
    RtspState.ERROR -> "خطأ"
}

fun stateColor(s: RtspState) = when (s) {
    RtspState.PLAYING -> Color(0xFF4CAF50)
    RtspState.CONNECTING, RtspState.BUFFERING -> Color(0xFFFFC107)
    RtspState.RECONNECTING -> Color(0xFFFF9800)
    RtspState.ERROR -> Color(0xFFF44336)
    RtspState.IDLE -> Color(0xFF9E9E9E)
}

@Composable
fun LiveScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.controller.status.collectAsStateWithLifecycle()
    val aspect by vm.controller.videoAspect.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val detectorError by vm.detectorError.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val s = settings
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        if (s == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
            return@Box
        }
        val video: @Composable (Modifier) -> Unit = { m ->
            VideoPane(
                modifier = m,
                vm = vm,
                aspect = aspect,
                people = people,
                status = status,
                settings = s,
                stats = stats,
            )
        }
        val panel: @Composable (Modifier) -> Unit = { m ->
            ControlPanel(
                modifier = m,
                settings = s,
                status = status,
                people = people,
                stats = stats,
                detectorError = detectorError,
                onToggleDetection = vm::setDetectionEnabled,
                onReconnect = vm::reconnectNow,
                onOpenSettings = onOpenSettings,
            )
        }
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(320.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Header(status = status, onOpenSettings = onOpenSettings)
                video(Modifier.fillMaxWidth())
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Header(status: RtspStatus, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("مراقب الأشخاص", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "اكتشاف وتتبع محلي · بدون تعرّف على الوجوه",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "الإعدادات") }
    }
}

@Composable
private fun StatusPill(state: RtspState) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(stateColor(state)))
        Spacer(Modifier.width(6.dp))
        Text(stateLabel(state), fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun HudPill(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x99000000))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun VideoPane(
    modifier: Modifier,
    vm: MainViewModel,
    aspect: Float,
    people: List<TrackedPerson>,
    status: RtspStatus,
    settings: AppSettings,
    stats: PipelineStats,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        // الصندوق بنسبة أبعاد الفيديو بالضبط: لذا تطابق إحداثيات الصناديق الطبيعية صورة الفيديو.
        Box(Modifier.aspectRatio(aspect)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val tv = TextureView(ctx)
                    vm.controller.attachView(tv)
                    vm.frameGrabber = { bmp -> tv.isAvailable && tv.getBitmap(bmp) != null }
                    tv
                },
                onRelease = {
                    vm.frameGrabber = null
                    vm.controller.detachView()
                },
            )
            if (settings.detectionEnabled && status.state == RtspState.PLAYING) {
                DetectionOverlay(people, Modifier.fillMaxSize())
            }
            // HUD
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HudPill("👤 ${if (settings.detectionEnabled) people.size else 0}")
                HudPill("FPS ${"%.0f".format(stats.videoFps)}")
                if (settings.detectionEnabled) {
                    HudPill("🔍 ${"%.2f".format(stats.maxScore)} · ☀ ${stats.frameLuma}")
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { StatusPill(status.state) }
        }

        if (status.state != RtspState.PLAYING) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (status.state != RtspState.ERROR && status.state != RtspState.IDLE) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                    Spacer(Modifier.size(12.dp))
                }
                Text(
                    status.message.ifBlank { stateLabel(status.state) },
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    settings: AppSettings,
    status: RtspStatus,
    people: List<TrackedPerson>,
    stats: PipelineStats,
    detectorError: String?,
    onToggleDetection: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (landscape) Header(status = status, onOpenSettings = onOpenSettings)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("الأشخاص", if (settings.detectionEnabled) "${people.size}" else "—", Modifier.weight(1f))
            StatCard("FPS البث", "%.0f".format(stats.videoFps), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                "FPS الاكتشاف",
                if (settings.detectionEnabled) "%.1f".format(stats.detectFps) else "—",
                Modifier.weight(1f),
            )
            StatCard(
                "زمن الاستدلال",
                if (settings.detectionEnabled && stats.inferenceMs > 0) "${stats.inferenceMs} ms" else "—",
                Modifier.weight(1f),
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("اكتشاف الأشخاص وتتبعهم", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (settings.detectionEnabled) settings.profile.label else "متوقف — يوفّر المعالج والبطارية",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.detectionEnabled, onCheckedChange = onToggleDetection)
                }
                if (settings.detectionEnabled && people.isNotEmpty()) {
                    Text(
                        "الحاليون: " + people.joinToString("، ") { "Person ${it.id}" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = TextStyle(textDirection = TextDirection.Content),
                    )
                }
                if (detectorError != null) {
                    Text(detectorError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReconnect, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("إعادة الاتصال")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("الإعدادات")
            }
        }

        Text(
            settings.rtspUrl.maskedRtsp(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = TextStyle(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
