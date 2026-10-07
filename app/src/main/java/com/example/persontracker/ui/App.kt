package com.example.persontracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun App(vm: MainViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val settings by vm.settings.collectAsStateWithLifecycle()

    // البثّ والمعالجة فقط أثناء ظهور التطبيق: يوفّر البطارية ويمنع العمل في الخلفية.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val s = settings
        if (showSettings && s != null) {
            SettingsScreen(
                settings = s,
                onSave = vm::saveSettings,
                onBack = { showSettings = false },
            )
        } else {
            LiveScreen(vm = vm, onOpenSettings = { showSettings = true })
        }
    }
}
