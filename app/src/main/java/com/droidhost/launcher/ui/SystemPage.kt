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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
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
    var openAbout = false
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
                SystemTabs.openAbout -> SYSTEM_ABOUT_TAB
                else -> SYSTEM_OVERVIEW_TAB
            },
        )
    }
    if (SystemTabs.openOverview) { tab = SYSTEM_OVERVIEW_TAB; SystemTabs.openOverview = false }
    if (SystemTabs.openComponents) { tab = SYSTEM_COMPONENTS_TAB; SystemTabs.openComponents = false }
    if (SystemTabs.openStorage) { tab = SYSTEM_STORAGE_TAB; SystemTabs.openStorage = false }
    if (SystemTabs.openUpdates) { tab = SYSTEM_UPDATES_TAB; SystemTabs.openUpdates = false }
    if (SystemTabs.openAbout) { tab = SYSTEM_ABOUT_TAB; SystemTabs.openAbout = false }
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
            HostTopBar(stringResource(R.string.rail_system), padH = if (LocalNarrowPane.current) 20.dp else 28.dp)
            ChipBar {
                Box(Modifier.weight(1f)) { SheetChips(tabs, tab, chipFocus, onPick = pick) }
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().then(if (tab == SYSTEM_UPDATES_TAB) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(bottom = if (tab == SYSTEM_UPDATES_TAB) 0.dp else maxOf(24.dp, LocalNavOverlayInset.current))) {
                when (tab) {
                    SYSTEM_OVERVIEW_TAB -> {
                        val ctx = LocalContext.current
                        val host = rememberMenuHost()
                        val first = remember { FocusRequester() }
                        var hideHomeFromRecents by remember { mutableStateOf(com.droidhost.launcher.core.AppUiPrefs.hideHomeFromRecents(ctx)) }
                        DeviceDetails(first)
                        SettingsGroup(stringResource(R.string.setup_group_device)) {
                            ToggleRow(
                                host, "home-screen", stringResource(R.string.setup_home),
                                if (s.homeScreenEnabled) stringResource(R.string.setup_home_on) else stringResource(R.string.setup_home_off),
                                s.homeScreenEnabled,
                            ) { a.onHomeScreen(it) }
                            if (s.homeScreenEnabled) {
                                ActionRow(stringResource(R.string.setup_default_home), s.defaultHomeLabel ?: stringResource(R.string.setup_choose_home), stringResource(R.string.setup_choose), a.onHomeApp)
                                ToggleRow(
                                    host, "hide-home-from-recents", stringResource(R.string.setup_hide_home_from_recents),
                                    stringResource(R.string.setup_hide_home_from_recents_hint), hideHomeFromRecents,
                                ) { hidden ->
                                    hideHomeFromRecents = hidden
                                    com.droidhost.launcher.core.AppUiPrefs.setHideHomeFromRecents(ctx, hidden)
                                }
                                ChoiceRow(
                                    host, "home-page", stringResource(R.string.setup_home_page), stringResource(R.string.setup_home_page_hint),
                                    com.droidhost.launcher.core.AppUiPrefs.homePageOptions.map { (value, label) -> value to stringResource(label) },
                                    s.homePage, onPick = a.onHomePage,
                                )
                            }
                        }
                        SettingsGroup(stringResource(R.string.setup_group_health)) {
                            val report = com.droidhost.launcher.storage.StorageState.report
                            LaunchedEffect(Unit) { com.droidhost.launcher.storage.StorageState.refresh(ctx) }
                            SettingsRow(stringResource(R.string.storage_internal), null) {
                                if (report == null) Text(stringResource(R.string.storage_measuring), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else Column(horizontalAlignment = Alignment.End) {
                                    Text(stringResource(R.string.storage_used_of,
                                        com.droidhost.launcher.storage.StorageState.format(report.total - report.free),
                                        com.droidhost.launcher.storage.StorageState.format(report.total)),
                                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(stringResource(R.string.storage_free, com.droidhost.launcher.storage.StorageState.format(report.free)),
                                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            val battery by androidx.compose.runtime.produceState<String?>(null) {
                                while (true) {
                                    val b = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                                    val level = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                                    val scale = b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)?.takeIf { it > 0 } ?: 100
                                    val tenths = b?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                                    val status = b?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, android.os.BatteryManager.BATTERY_STATUS_UNKNOWN)
                                        ?: android.os.BatteryManager.BATTERY_STATUS_UNKNOWN
                                    value = if (level < 0) null else {
                                        val pct = level * 100 / scale
                                        val statusText = ctx.getString(when (status) {
                                            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> R.string.setup_battery_charging
                                            android.os.BatteryManager.BATTERY_STATUS_FULL -> R.string.setup_battery_full
                                            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING, android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.setup_battery_discharging
                                            else -> R.string.setup_battery_unknown
                                        })
                                        if (tenths >= 0) ctx.getString(R.string.setup_battery_value_temp, pct, statusText, tenths / 10)
                                        else ctx.getString(R.string.setup_battery_value, pct, statusText)
                                    }
                                    kotlinx.coroutines.delay(30_000)
                                }
                            }
                            SettingsRow(stringResource(R.string.setup_battery), null) {
                                Text(battery ?: stringResource(R.string.setup_battery_unknown), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            SettingsRow(stringResource(R.string.setup_phantom), null) {
                                Text(com.droidhost.launcher.core.PhantomProcessLimit.title(ctx, s.phantomProcessStatus),
                                    fontSize = 13.sp,
                                    color = if (com.droidhost.launcher.core.PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)) AttentionAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        s.tpUbwcMode?.let { ubwc ->
                            SettingsGroup(stringResource(R.string.setup_group_device)) {
                                ChoiceRow(
                                    host, "tp-ubwc", stringResource(R.string.gpu_ubwc_title), stringResource(R.string.gpu_ubwc_hint),
                                    listOf(
                                        com.droidhost.launcher.session.SessionPrefs.TP_UBWC_AUTO to stringResource(R.string.common_auto),
                                        com.droidhost.launcher.session.SessionPrefs.TP_UBWC_ON to stringResource(R.string.widgets_on),
                                        com.droidhost.launcher.session.SessionPrefs.TP_UBWC_OFF to stringResource(R.string.widgets_off),
                                    ), ubwc, onPick = a.onTpUbwcMode,
                                )
                                if (s.tpUbwcRestartRequired) {
                                    if (s.canRestartApp) ActionRow(stringResource(R.string.gpu_ubwc_restart), null, stringResource(R.string.gpu_restart_now), a.onRestartApp)
                                    else Note(stringResource(R.string.gpu_ubwc_restart))
                                }
                            }
                        }
                    }
                    SYSTEM_COMPONENTS_TAB -> {
                        SettingsGroup(stringResource(R.string.comp_gpu_drivers)) {
                            SettingCard(
                                stringResource(R.string.comp_runtime_driver),
                                s.gpuRuntimeDriver.ifBlank { stringResource(R.string.gpu_runtime_default) },
                                "system:gpu-runtime", Modifier.fillMaxWidth(),
                                badge = stringResource(R.string.setup_tag_needs_restart),
                            ) { a.onComponentsTab(GPU_TAB) }
                            SettingCard(
                                stringResource(R.string.comp_display_driver),
                                s.gpuDisplayDriver.ifBlank { stringResource(R.string.gpu_auto_by_gpu) },
                                "system:gpu-display", Modifier.fillMaxWidth(),
                                badge = stringResource(R.string.setup_tag_needs_restart),
                            ) { a.onComponentsTab(GPU_TAB) }
                        }
                        SettingsGroup(stringResource(R.string.comp_title)) {
                            SettingCard(
                                stringResource(R.string.comp_runtime_components),
                                stringResource(R.string.system_components_open),
                                "system:components", Modifier.fillMaxWidth(),
                                badge = stringResource(R.string.setup_tag_needs_restart),
                            ) { a.onComponents(true) }
                            SettingCard(
                                stringResource(R.string.comp_proton),
                                stringResource(R.string.system_components_open),
                                "system:proton", Modifier.fillMaxWidth(),
                                badge = stringResource(R.string.setup_tag_needs_restart),
                            ) { a.onComponentsTab(PROTON_TAB) }
                        }
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
