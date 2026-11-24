package com.droidhost.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R
import com.droidhost.launcher.session.SessionLogShare
import com.droidhost.launcher.update.AppUpdates

internal object SystemTabs {
    var openOverview = false
    var openComponents = false
    var openStorage = false
    var openUpdates = false
}

private const val SYSTEM_OVERVIEW_TAB = 0
private const val SYSTEM_COMPONENTS_TAB = 1
private const val SYSTEM_STORAGE_TAB = 2
private const val SYSTEM_UPDATES_TAB = 3
private const val SYSTEM_ABOUT_TAB = 4

@Composable
internal fun SystemPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    val tabs = listOf(
        stringResource(R.string.system_tab_overview),
        stringResource(R.string.system_tab_components),
        stringResource(R.string.system_tab_storage),
        stringResource(R.string.system_tab_updates),
        stringResource(R.string.system_tab_about),
    )
    var tab by rememberSaveable {
        mutableStateOf(
            when {
                SystemTabs.openComponents -> SYSTEM_COMPONENTS_TAB
                SystemTabs.openStorage -> SYSTEM_STORAGE_TAB
                SystemTabs.openUpdates -> SYSTEM_UPDATES_TAB
                else -> SYSTEM_OVERVIEW_TAB
            },
        )
    }
    if (SystemTabs.openOverview) { tab = SYSTEM_OVERVIEW_TAB; SystemTabs.openOverview = false }
    if (SystemTabs.openComponents) { tab = SYSTEM_COMPONENTS_TAB; SystemTabs.openComponents = false }
    if (SystemTabs.openStorage) { tab = SYSTEM_STORAGE_TAB; SystemTabs.openStorage = false }
    if (SystemTabs.openUpdates) { tab = SYSTEM_UPDATES_TAB; SystemTabs.openUpdates = false }
    val chipFocus = remember { List(tabs.size) { FocusRequester() } }
    var tabTurned by remember { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> tab = i; tabTurned = true }
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(com.droidhost.launcher.storage.StorageState.openRequests) {
        if (com.droidhost.launcher.storage.StorageState.openPending) {
            com.droidhost.launcher.storage.StorageState.openPending = false
            SystemTabs.openStorage = false
            tab = SYSTEM_STORAGE_TAB
        }
    }
    LaunchedEffect(tab) {
        if (tabTurned && inputModeManager.inputMode == InputMode.Keyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { chipFocus[tab].requestFocus() }
        }
    }
    Rise(0, Modifier.fillMaxSize()) {
        Column(
            modifier = modifier.fillMaxSize().bumpers(
                onPrevious = { pick((tab + tabs.size - 1) % tabs.size) },
                onNext = { pick((tab + 1) % tabs.size) },
            ),
        ) {
            PageHeader(stringResource(R.string.rail_system)) {
                Box(Modifier.weight(1f)) { SheetChips(tabs, tab, chipFocus, onPick = pick) }
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().then(if (tab == SYSTEM_UPDATES_TAB) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(bottom = if (tab == SYSTEM_UPDATES_TAB) 0.dp else maxOf(24.dp, LocalNavOverlayInset.current))) {
                when (tab) {
                    SYSTEM_OVERVIEW_TAB -> {
                        val first = remember { FocusRequester() }
                        DeviceDetails(first)
                    }
                    SYSTEM_COMPONENTS_TAB -> {
                        SettingCard(
                            stringResource(R.string.system_tab_components),
                            stringResource(R.string.system_components_open),
                            "system:components", Modifier.fillMaxWidth(),
                        ) { a.onComponents(true) }
                        Box(Modifier.fillMaxWidth().padding(top = 10.dp)) { Note(stringResource(R.string.system_components_hint)) }
                    }
                    SYSTEM_STORAGE_TAB -> StoragePanel(s, a)
                    SYSTEM_UPDATES_TAB -> UpdatesPage(s, a, Modifier.fillMaxSize())
                    SYSTEM_ABOUT_TAB -> SystemAbout(s, a)
                }
            }
        }
    }
}

@Composable
private fun SystemAbout(s: FrontEndState, a: FrontEndActions) {
    val colors = MaterialTheme.colorScheme
    val me = remember { AppUpdates.installed() }
    SettingsGroup(stringResource(R.string.system_tab_about)) {
        SettingsRow(stringResource(R.string.system_about_version), null) {
            Text(stringResource(R.string.system_about_version_value, me.version, me.versionCode), fontSize = 14.sp, color = colors.onSurfaceVariant)
        }
        SettingsRow(stringResource(R.string.system_about_build), null) {
            Text(s.buildLabel, fontSize = 14.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ActionRow(
            stringResource(R.string.setup_latest_logs), stringResource(R.string.drawer_logs_hint),
            stringResource(R.string.drawer_share_logs), a.onShareLogs,
            progress = SessionLogShare.progress,
        )
    }
    SettingsGroup(stringResource(R.string.system_about_license)) {
        Text(
            stringResource(R.string.system_about_license_text),
            fontSize = 13.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        )
        Text(
            stringResource(R.string.system_about_upstream),
            fontSize = 13.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        )
    }
}
