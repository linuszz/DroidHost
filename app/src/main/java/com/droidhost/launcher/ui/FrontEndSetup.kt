package com.droidhost.launcher.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.droidhost.launcher.core.AppLanguage
import com.droidhost.launcher.core.DownloadSource
import com.droidhost.launcher.session.SessionPrefs
import com.droidhost.launcher.stores.download.StoreDownloadTier
import com.droidhost.launcher.R


internal enum class CheckState { OK, WARN, BUSY }

internal object SetupTabs {
    var openLauncher = false
    var focusTabs = false
    var openDisplay = false
    var openInput = false
    var openPerformance = false
    var openAppearance = false
    var openNetwork = false
    var openGlasses = false
    var openAdvanced = false
}

private const val TAB_DISPLAY = "display"
private const val TAB_INPUT = "input"
private const val TAB_PERFORMANCE = "performance"
private const val TAB_APPEARANCE = "appearance"
private const val TAB_LAUNCHER = "launcher"
private const val TAB_NETWORK = "network"
private const val TAB_GLASSES = "glasses"
private const val TAB_ADVANCED = "advanced"

@Composable
internal fun CheckRow(state: CheckState, title: String, detail: String?, divider: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = when (state) {
        CheckState.OK -> pal.good
        CheckState.WARN -> AttentionAmber
        CheckState.BUSY -> pal.signal
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (state == CheckState.WARN) AttentionAmber.copy(alpha = 0.07f) else Color.Transparent)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f))) {
            Icon(
                when (state) {
                    CheckState.OK -> Icons.Filled.Check
                    CheckState.WARN -> Icons.Filled.PriorityHigh
                    CheckState.BUSY -> Icons.Filled.Refresh
                },
                contentDescription = when (state) {
                    CheckState.OK -> stringResource(R.string.setup_check_done)
                    CheckState.WARN -> stringResource(R.string.setup_check_warn)
                    CheckState.BUSY -> stringResource(R.string.setup_check_busy)                },
                tint = tint, modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) action()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
internal fun SetupPanel(
    s: FrontEndState,
    a: FrontEndActions,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
    onPairWirelessDebugging: () -> Unit,
) {
    val host = rememberMenuHost()
    val ctx = LocalContext.current
    var editSgdbKey by remember { mutableStateOf(false) }
    var editDownloadPrefix by remember { mutableStateOf(false) }
    var editDownloadProxy by remember { mutableStateOf(false) }
    if (editSgdbKey) SgdbKeyDialog(s.sgdbUserKey, onSave = { a.onSgdbKey(it); editSgdbKey = false }, onDismiss = { editSgdbKey = false })

    if (editDownloadPrefix) TextEntryDialog(
        stringResource(R.string.setup_download_prefix), s.downloadPrefix,
        onDismiss = { editDownloadPrefix = false },
        onSave = { a.onDownloadPrefix(it); editDownloadPrefix = false },
    )
    if (editDownloadProxy) TextEntryDialog(
        stringResource(R.string.setup_download_proxy), s.downloadProxy,
        onDismiss = { editDownloadProxy = false },
        onSave = { a.onDownloadProxy(it); editDownloadProxy = false },
    )
    val tabs = listOf(
        TAB_DISPLAY to stringResource(R.string.setup_tab_session),
        TAB_INPUT to stringResource(R.string.setup_tab_controller),
        TAB_PERFORMANCE to stringResource(R.string.setup_tab_performance),
        TAB_APPEARANCE to stringResource(R.string.setup_tab_appearance),
        TAB_LAUNCHER to stringResource(R.string.setup_tab_launcher),
        TAB_NETWORK to stringResource(R.string.setup_tab_sync),
        TAB_GLASSES to stringResource(R.string.setup_tab_glasses),
        TAB_ADVANCED to stringResource(R.string.setup_tab_diagnostics),
    )
    // Explicit saveable key: retired Int-index state must not restore onto the new key order.
    var tab by rememberSaveable(key = "setup-tab-key") {
        mutableStateOf(
            when {
                SetupTabs.openDisplay -> TAB_DISPLAY
                SetupTabs.openInput -> TAB_INPUT
                SetupTabs.openPerformance -> TAB_PERFORMANCE
                SetupTabs.openAppearance -> TAB_APPEARANCE
                SetupTabs.openLauncher -> TAB_LAUNCHER
                SetupTabs.openNetwork -> TAB_NETWORK
                SetupTabs.openGlasses -> TAB_GLASSES
                SetupTabs.openAdvanced -> TAB_ADVANCED
                else -> TAB_DISPLAY
            },
        )
    }
    if (SetupTabs.openDisplay) { tab = TAB_DISPLAY; SetupTabs.openDisplay = false }
    if (SetupTabs.openInput) { tab = TAB_INPUT; SetupTabs.openInput = false }
    if (SetupTabs.openPerformance) { tab = TAB_PERFORMANCE; SetupTabs.openPerformance = false }
    if (SetupTabs.openAppearance) { tab = TAB_APPEARANCE; SetupTabs.openAppearance = false }
    if (SetupTabs.openLauncher) { tab = TAB_LAUNCHER; SetupTabs.openLauncher = false }
    if (SetupTabs.openNetwork) { tab = TAB_NETWORK; SetupTabs.openNetwork = false }
    if (SetupTabs.openGlasses) { tab = TAB_GLASSES; SetupTabs.openGlasses = false }
    if (SetupTabs.openAdvanced) { tab = TAB_ADVANCED; SetupTabs.openAdvanced = false }
    var logsRefresh by remember { mutableStateOf(0) }
    var deleteSessionLogs by remember { mutableStateOf<java.io.File?>(null) }
    val chipFocus = remember { List(tabs.size) { FocusRequester() } }
    var tabTurned by remember { mutableStateOf(false) }
    val pick: (String) -> Unit = { key -> tab = key; tabTurned = true; if (key == TAB_ADVANCED) logsRefresh++ }
    val tabIndex = tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    val inputModeManager = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            a.onRefreshPhantomStatus()
        }
    }
    LaunchedEffect(com.droidhost.launcher.sync.Syncthing.openRequests) {
        if (com.droidhost.launcher.sync.Syncthing.openPending) {
            com.droidhost.launcher.sync.Syncthing.openPending = false
            tab = TAB_NETWORK
        }
    }
    var syncAdvanced by rememberSaveable { mutableStateOf(false) }
    if (syncAdvanced) {
        androidx.activity.compose.BackHandler { syncAdvanced = false }
        SyncAdvancedPage(host) { syncAdvanced = false }
        return
    }
    LaunchedEffect(tab) {
        if (tabTurned && inputModeManager.inputMode == InputMode.Keyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { chipFocus[tabIndex].requestFocus() }
        }
    }
    val runtimeOk = s.ready && !s.busy && !s.removalPending
    Rise(0, Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().bumpers(
                onPrevious = { pick(tabs[(tabIndex + tabs.size - 1) % tabs.size].first) },
                onNext = { pick(tabs[(tabIndex + 1) % tabs.size].first) },
            ),
        ) {
            HostTopBar(stringResource(R.string.setup_title), padH = if (LocalNarrowPane.current) 20.dp else 28.dp)
            ChipBar {
                Box(Modifier.weight(1f)) { SheetChips(tabs.map { it.second }, tabIndex, chipFocus, onPick = { i -> pick(tabs[i].first) }) }
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().then(if (tab == TAB_INPUT) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(bottom = if (tab == TAB_INPUT) LocalNavOverlayInset.current else maxOf(24.dp, LocalNavOverlayInset.current))) {
                when (tab) {
                    TAB_DISPLAY -> {
                        var safeArea by remember { mutableStateOf(SessionPrefs.safeAreaMode(ctx)) }
                        var refreshChoice by remember { mutableStateOf(SessionPrefs.refreshRateChoice(ctx)) }
                        var upscaler by remember { mutableStateOf(SessionPrefs.upscaler(ctx)) }
                        var sharpness by remember { mutableStateOf(SessionPrefs.upscaleSharpness(ctx)) }
                        var pipAuto by remember { mutableStateOf(SessionPrefs.pipAutoEnter(ctx)) }
                        var effects by remember { mutableStateOf(SessionPrefs.screenEffects(ctx)) }
                        var effectsExpanded by rememberSaveable { mutableStateOf(false) }
                        SettingsGroup(stringResource(R.string.setup_group_launcher_display)) {
                            ChoiceRow(
                                host, "launcher-orientation", stringResource(R.string.setup_launcher_orientation), null,
                                com.droidhost.launcher.core.AppUiPrefs.orientationOptions.map { (value, label) -> value to stringResource(label) },
                                s.launcherOrientation, onPick = a.onLauncherOrientation,
                            )
                            ToggleRow(
                                host, "launcher-fullscreen", stringResource(R.string.setup_fullscreen),
                                if (s.launcherFullscreen) stringResource(R.string.setup_fullscreen_on) else stringResource(R.string.setup_fullscreen_off),
                                s.launcherFullscreen,
                            ) { a.onLauncherFullscreen(it) }
                            if (s.dualScreenDevice) {
                                val dualModes = listOf(
                                    com.droidhost.launcher.input.DualScreen.MODE_AUTO to stringResource(R.string.setup_dual_screen_auto),
                                    com.droidhost.launcher.input.DualScreen.MODE_ON to stringResource(R.string.setup_dual_screen_on),
                                    com.droidhost.launcher.input.DualScreen.MODE_OFF to stringResource(R.string.setup_dual_screen_off),
                                )
                                ChoiceRow(
                                    host, "dual-screen", stringResource(R.string.setup_dual_screen_launcher), stringResource(R.string.setup_dual_screen_launcher_hint),
                                    dualModes, s.dualScreenMode, onPick = a.onDualScreenMode,
                                )
                                ChoiceRow(
                                    host, "dual-screen-sessions", stringResource(R.string.setup_dual_screen_sessions), stringResource(R.string.setup_dual_screen_sessions_hint),
                                    dualModes, s.dualScreenSessions, onPick = a.onDualScreenSessions,
                                )
                            }
                        }
                        SettingsGroup(stringResource(R.string.setup_group_session_display)) {
                            ChoiceRow(
                                host, "orientation", stringResource(R.string.setup_session_orientation), stringResource(R.string.setup_session_orientation_hint),
                                SessionPrefs.orientationOptions.map { (value, label) -> value to stringResource(label) },
                                s.orientation, onPick = a.onOrientation,
                            )
                            ChoiceRow(
                                host, "safe-area", stringResource(R.string.safe_area_mode), stringResource(R.string.safe_area_mode_hint),
                                SessionPrefs.safeAreaModeOptions.map { (value, label) -> value to stringResource(label) },
                                safeArea, onPick = { mode -> safeArea = mode; SessionPrefs.setSafeAreaMode(ctx, mode) },
                            )
                            // Same rate list as the session toolbar: auto (0) plus the panel's supported modes.
                            val refreshRates = remember(ctx) {
                                val activity = ctx as? android.app.Activity
                                val display = if (Build.VERSION.SDK_INT >= 30) activity?.display else activity?.windowManager?.defaultDisplay
                                val cur = display?.mode
                                display?.supportedModes
                                    ?.filter { cur != null && it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                                    ?.map { it.refreshRate.toInt() }?.distinct()?.sortedDescending().orEmpty()
                            }
                            ChoiceRow(
                                host, "refresh-rate", stringResource(R.string.setup_refresh_rate), stringResource(R.string.setup_refresh_rate_hint),
                                listOf(0 to stringResource(R.string.common_auto)) + refreshRates.map { it to "$it Hz" },
                                refreshChoice, onPick = { hz -> refreshChoice = hz; SessionPrefs.setRefreshRateChoice(ctx, hz) },
                            )
                            ChoiceRow(
                                host, "upscaler", stringResource(R.string.display_filter), null,
                                SessionPrefs.upscalerChoices(ctx), upscaler,
                                note = stringResource(R.string.display_filter_note),
                                onPick = { mode -> upscaler = mode; SessionPrefs.setUpscaler(ctx, mode) },
                            )
                            if (SessionPrefs.upscalerHasSharpness(upscaler)) SliderRow(
                                stringResource(R.string.display_sharpness), null, sharpness, 0..100, step = 5,
                                format = { "$it%" }, onChange = { pct -> sharpness = pct; SessionPrefs.setUpscaleSharpness(ctx, pct) },
                            )
                            // 界面缩放：会话内桌面输出的缩放，与会话快捷键同源（SessionPrefs.guestUiScalePercent，设计 §4.3）。
                            var guestUiScale by remember { mutableStateOf(SessionPrefs.guestUiScalePercent(ctx)) }
                            ChoiceRow(
                                host, "guest-ui-scale", stringResource(R.string.quick_ui_scale),
                                stringResource(R.string.setup_guest_ui_scale_hint),
                                listOf(100 to "100%", 150 to "150%", 200 to "200%"),
                                guestUiScale,
                                badge = stringResource(R.string.setup_tag_needs_restart),
                                onPick = { pct -> guestUiScale = pct; SessionPrefs.setGuestUiScalePercent(ctx, pct) },
                            )
                            ToggleRow(host, "pip-auto", stringResource(R.string.pip_auto), null, pipAuto) { on ->
                                pipAuto = on
                                SessionPrefs.setPipAutoEnter(ctx, on)
                            }
                        }
                        ScreenEffectsGroup(host, effects, upscaler,
                            onEffects = { e -> effects = e; SessionPrefs.setScreenEffects(ctx, e) },
                            onUpscaler = { mode -> upscaler = mode; SessionPrefs.setUpscaler(ctx, mode) },
                            advancedExpanded = effectsExpanded, onAdvancedToggle = { effectsExpanded = !effectsExpanded }) { Modifier }
                        // 帧生成：写 frame_gen 的全局键，每游戏覆盖仍留在游戏上下文（设计 §4.5 / 原型⑥）。
                        var frameGen by remember { mutableStateOf(com.droidhost.launcher.gpu.FrameGen.mode(ctx)) }
                        val losslessReady = remember(ctx) { com.droidhost.launcher.gpu.Lossless.state(ctx).ready }
                        SettingsGroup(stringResource(R.string.frame_gen_title)) {
                            ChoiceRow(
                                host, "frame-gen", stringResource(R.string.frame_gen_title),
                                stringResource(R.string.setup_frame_gen_global_hint),
                                buildList {
                                    add(com.droidhost.launcher.gpu.FrameGen.ENGINE_OFF to stringResource(R.string.frame_gen_off))
                                    add(com.droidhost.launcher.gpu.FrameGen.ENGINE_DDFG to stringResource(R.string.frame_gen_ddfg, 2))
                                    if (losslessReady) add(com.droidhost.launcher.gpu.FrameGen.ENGINE_LSFG to stringResource(R.string.frame_gen_lsfg, 2))
                                },
                                frameGen.engine,
                                onPick = { engine ->
                                    val mode = com.droidhost.launcher.gpu.FrameGen.Mode(engine, multiplier = frameGen.multiplier)
                                    com.droidhost.launcher.gpu.FrameGen.set(ctx, mode)
                                    frameGen = mode
                                },
                            )
                        }
                    }
                    TAB_INPUT -> {
                        // 触摸与鼠标等全局输入项已并入内嵌输入页（同一滚动列表，见 InputSettingsPage）。
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (s.controller != null && a.controller != null) InputSettingsPage(onBack = { pick(TAB_INPUT) }, embedded = true)
                            else Note(stringResource(R.string.setup_controller_unavailable))
                        }
                        if (s.dualScreenDevice) {
                            SettingsGroup(stringResource(R.string.setup_group_device)) {
                                if (s.dualScreenSessions != com.droidhost.launcher.input.DualScreen.MODE_OFF) {
                                    ToggleRow(host, "dual-screen-controls", stringResource(R.string.setup_dual_screen_controls),
                                        stringResource(R.string.setup_dual_screen_controls_hint), s.dualScreenControls) { a.onDualScreenControls(it) }
                                }
                                ToggleRow(host, "android-text-field-keyboard", stringResource(R.string.setup_android_text_field_keyboard),
                                    null, s.androidTextFieldKeyboard) { a.onAndroidTextFieldKeyboard(it) }
                            }
                        }
                    }
                    TAB_PERFORMANCE -> {
                        DroidHostHudSettings(host)
                        SettingsGroup(stringResource(R.string.setup_group_memory)) {
                            ChoiceRow(
                                host, "memory-hud", stringResource(R.string.setup_memory_hud), stringResource(R.string.setup_memory_hud_hint),
                                listOf(
                                    com.droidhost.launcher.session.SessionPrefs.MEMORY_HUD_OFF to stringResource(R.string.game_env_off),
                                    com.droidhost.launcher.session.SessionPrefs.MEMORY_HUD_SIMPLE to stringResource(R.string.setup_memory_hud_simple),
                                    com.droidhost.launcher.session.SessionPrefs.MEMORY_HUD_ADVANCED to stringResource(R.string.setup_memory_hud_advanced),
                                ),
                                s.memoryHud, onPick = a.onMemoryHud,
                            )
                        }
                    }
                    TAB_APPEARANCE -> {
                        SettingsGroup(stringResource(R.string.setup_group_look)) {
                            SettingsRow(stringResource(R.string.setup_theme), stringResource(R.string.setup_theme_hint)) {
                                Box {
                                    ValueChip(stringResource(Themes.byId(s.theme).label), host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                                    AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = stringResource(R.string.setup_theme)) { firstItemFocus ->
                                        Themes.all.forEachIndexed { index, theme ->
                                            MenuItem(stringResource(theme.label), checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                                                a.onTheme(theme.id)
                                                host.open = null
                                            }
                                        }
                                    }
                                }
                            }
                            if (s.theme == Themes.INNER_GAMER) RgbSpeedRow()
                            if (s.theme == Themes.GRADIENT) GradientColorRows()
                            ChoiceRow(
                                host, "app-scale", stringResource(R.string.setup_app_scale), stringResource(R.string.setup_app_scale_hint),
                                com.droidhost.launcher.core.AppUiPrefs.scales.map { percent ->
                                    percent to stringResource(
                                        if (percent == com.droidhost.launcher.core.AppUiPrefs.DEFAULT_SCALE) R.string.setup_app_scale_default
                                        else R.string.ctrl_percent, percent,
                                    )
                                }, s.appScale, onPick = a.onAppScale,
                            )
                            SliderRow(
                                stringResource(R.string.setup_font_scale), stringResource(R.string.setup_font_scale_hint),
                                s.fontScale, SessionPrefs.FONT_SCALE_RANGE, step = 5, format = { "$it%" },
                                onChange = a.onFontScale,
                            )
                            ChoiceRow(
                                host, "launcher-icon", stringResource(R.string.setup_launcher_icon), null,
                                com.droidhost.launcher.LauncherIcon.options.map { (style, label) -> style to stringResource(label) },
                                s.launcherIcon, onPick = a.onLauncherIcon,
                            )
                            ToggleRow(host, "launcher-animations", stringResource(R.string.setup_animations), null,
                                s.animationsEnabled, onChange = a.onAnimationsEnabled)
                            ToggleRow(host, "launcher-reduce-transparency", stringResource(R.string.setup_reduce_transparency), stringResource(R.string.setup_reduce_transparency_hint),
                                s.reduceTransparency, onChange = a.onReduceTransparency)
                            val railContext = androidx.compose.ui.platform.LocalContext.current
                            remember { RailLabels.load(railContext) }
                            ToggleRow(host, "rail-labels", stringResource(R.string.setup_rail_labels), stringResource(R.string.setup_rail_labels_hint), RailLabels.shown) { RailLabels.set(railContext, it) }
                            LanguageRow(host, s.language, a.onLanguage)
                            if (s.soundtracksEnabled) ToggleRow(host, "soundtracks-ingame", stringResource(R.string.setup_soundtracks_ingame), stringResource(R.string.setup_soundtracks_ingame_hint), s.soundtracksIngame, onChange = a.onSoundtracksIngame)
                        }
                        // Game-art rows stay here until §4.7 decides their fate.
                        SettingsGroup(stringResource(R.string.setup_group_art)) {
                            ToggleRow(host, "added-art", stringResource(R.string.mode_added_art), stringResource(R.string.mode_added_art_hint), s.addedGamesArt) { a.onAddedGamesArt(it) }
                            ActionRow(
                                stringResource(R.string.mode_sgdb_key), if (s.sgdbUserKey) stringResource(R.string.mode_sgdb_key_yours) else null,
                                stringResource(R.string.mode_sgdb_edit), onClick = { editSgdbKey = true },
                            )
                        }
                    }
                    TAB_LAUNCHER -> {
                        var defaultTab by remember { mutableStateOf(SessionPrefs.defaultNavTab(ctx)) }
                        TabsSettingsGroup(host, s, a, defaultTab) { key ->
                            defaultTab = key
                            SessionPrefs.setDefaultNavTab(ctx, key)
                        }
                        SettingsGroup(stringResource(R.string.setup_group_cache)) {
                            ToggleRow(
                                host, "clear-cached-apps", stringResource(R.string.setup_clear_cached),
                                stringResource(if (s.adbPaired) R.string.setup_clear_cached_hint else R.string.setup_clear_cached_unpaired),
                                s.clearCachedApps, enabled = s.adbPaired || s.clearCachedApps,
                            ) { a.onClearCachedApps(it) }
                            ChoiceRow(
                                host, "background-app-limit", stringResource(R.string.setup_bg_limit),
                                stringResource(if (s.adbPaired) R.string.setup_bg_limit_hint else R.string.setup_clear_cached_unpaired),
                                listOf(
                                    com.droidhost.launcher.core.BackgroundAppLimit.OFF to stringResource(R.string.game_env_off),
                                    4 to stringResource(R.string.setup_bg_limit_4),
                                    1 to stringResource(R.string.setup_bg_limit_1),
                                    0 to stringResource(R.string.setup_bg_limit_0),
                                ),
                                s.backgroundAppLimit, enabled = s.adbPaired || s.backgroundAppLimit != com.droidhost.launcher.core.BackgroundAppLimit.OFF,
                                onPick = a.onBackgroundAppLimit,
                            )
                            if (!s.adbPaired) ActionRow(
                                stringResource(R.string.setup_adb_pair), stringResource(R.string.setup_adb_pair_hint),
                                stringResource(R.string.setup_adb_pair_button), onPairWirelessDebugging,
                            )
                        }
                        // 开机动画是全局项：从模式设置迁到「启动器与 Tab」（设计 §4.3 / 原型⑥屏）。
                        if (s.bootMovie != null) BootMovieGroup(
                            ModeSettings(
                                bootMovie = s.bootMovie,
                                bootMovieSound = s.bootMovieSound,
                                bootMovieImporting = s.bootMovieImporting,
                                bootMovieProgress = s.bootMovieProgress,
                            ),
                            ModeSettingsActions(
                                onResolution = {},
                                onHdr = {},
                                onBootMovie = a.onBootMovie,
                                onBootMovieSound = a.onBootMovieSound,
                                onImportBootMovie = a.onImportBootMovie,
                                onBootMovieChanged = a.onBootMovieChanged,
                            ),
                            host,
                        )
                    }
                    TAB_NETWORK -> {
                        SyncPanel(host, runtimeOk) { syncAdvanced = true }
                        SettingsGroup(stringResource(R.string.setup_group_download)) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) { DownloadControls(s, a) }
                            ChoiceRow(
                                host, "download-source", stringResource(R.string.setup_download_source), null,
                                listOf(
                                    DownloadSource.MODE_AUTO to stringResource(R.string.setup_download_source_auto),
                                    DownloadSource.MODE_DIRECT to stringResource(R.string.setup_download_source_direct),
                                    DownloadSource.MODE_MIRROR to stringResource(R.string.setup_download_source_mirror),
                                    DownloadSource.MODE_CUSTOM to stringResource(R.string.setup_download_source_custom),
                                ),
                                s.downloadSource, onPick = a.onDownloadSource,
                            )
                            if (s.downloadSource == DownloadSource.MODE_MIRROR) {
                                ChoiceRow(
                                    host, "download-mirror", stringResource(R.string.setup_download_mirror), null,
                                    DownloadSource.MIRRORS.map { it.host to it.host },
                                    s.downloadMirror, onPick = a.onDownloadMirror,
                                )
                            }
                            if (s.downloadSource == DownloadSource.MODE_CUSTOM) {
                                ActionRow(
                                    stringResource(R.string.setup_download_prefix),
                                    s.downloadPrefix.takeIf { it.isNotEmpty() },
                                    stringResource(R.string.mode_sgdb_edit),
                                    onClick = { editDownloadPrefix = true },
                                )
                            }
                            ActionRow(
                                stringResource(R.string.setup_download_proxy),
                                s.downloadProxy.takeIf { it.isNotEmpty() },
                                stringResource(R.string.mode_sgdb_edit),
                                onClick = { editDownloadProxy = true },
                            )
                        }
                    }
                    TAB_GLASSES -> GlassesSetupPanel()
                    TAB_ADVANCED -> {
                        SettingsGroup(stringResource(R.string.setup_tab_diagnostics)) {
                            ToggleRow(host, "logs", stringResource(R.string.setup_logs), stringResource(R.string.setup_logs_hint), s.logsEnabled) { a.onLogs() }
                            ToggleRow(
                                host, "enhanced-diagnostics", stringResource(R.string.setup_enhanced_diagnostics),
                                stringResource(R.string.setup_enhanced_diagnostics_hint), s.enhancedDiagnosticsArmed,
                            ) { a.onEnhancedDiagnostics() }
                            // game-debug-logs / vacdiag / steam-gpu-drawing are game-facing rows pending §4.7.
                            ToggleRow(
                                host, "game-debug-logs", stringResource(R.string.setup_game_debug_logs),
                                stringResource(if (s.gameDebugLogsNeedsRestart) R.string.setup_game_debug_logs_restart else R.string.setup_game_debug_logs_hint),
                                s.gameDebugLogsEnabled,
                                enabled = s.logsEnabled,
                            ) { a.onGameDebugLogs() }
                            ToggleRow(host, "vacdiag", stringResource(R.string.setup_vac_diag), stringResource(R.string.setup_vac_diag_hint), s.vacDiagnostics) { a.onVacDiagnostics() }
                            ToggleRow(host, "agent-commands", stringResource(R.string.setup_agent_commands), null, s.agentCommands) { a.onAgentCommands() }
                            if (com.droidhost.launcher.agent.AgentHost.available) {
                                val agentContext = androidx.compose.ui.platform.LocalContext.current
                                ToggleRow(host, "agent-pet", stringResource(R.string.setup_agent_pet), stringResource(R.string.setup_agent_pet_hint), AgentPet.enabled) {
                                    AgentPet.set(agentContext, it)
                                }
                                if (AgentPet.enabled && !AgentPet.allowed) ActionRow(
                                    stringResource(R.string.setup_agent_pet_float), stringResource(R.string.setup_agent_pet_float_hint),
                                    stringResource(R.string.setup_agent_pet_allow), onClick = { AgentPet.requestFloating(agentContext) },
                                )
                            }
                            ToggleRow(host, "graphics-logs", stringResource(R.string.setup_graphics_logs), stringResource(R.string.setup_graphics_logs_hint), s.graphicsLogs) { a.onGraphicsLogs() }
                            if (!s.adreno && com.droidhost.launcher.session.SessionPrefs.steamGpuCompositingAllowed()) ToggleRow(host, "steam-gpu-drawing", stringResource(R.string.setup_steam_gpu_drawing), stringResource(R.string.setup_steam_gpu_drawing_hint), s.steamGpuDrawing) { a.onSteamGpuDrawing() }
                            ActionRow(stringResource(R.string.setup_latest_logs), stringResource(R.string.drawer_logs_hint), stringResource(R.string.drawer_share_logs), a.onShareLogs,
                                progress = com.droidhost.launcher.session.SessionLogShare.progress)
                            androidx.compose.material3.HorizontalDivider()
                            Text(stringResource(R.string.setup_log_sessions), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            Text(stringResource(R.string.setup_log_export_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp))
                            val sessionLogs = remember(ctx, logsRefresh) { com.droidhost.launcher.session.SessionLogShare.sessions(ctx).asReversed() }
                            if (sessionLogs.isEmpty()) {
                                Text(stringResource(R.string.setup_log_empty), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                            sessionLogs.forEach { folder ->
                                val active = folder == com.droidhost.launcher.session.SessionPaths.current()
                                var details by remember(folder) { mutableStateOf<com.droidhost.launcher.session.SessionLogShare.Details?>(null) }
                                LaunchedEffect(folder, logsRefresh) {
                                    details = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        com.droidhost.launcher.session.SessionLogShare.details(folder)
                                    }
                                }
                                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(folder.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    details?.let { detail ->
                                        Text(
                                            stringResource(R.string.setup_log_size_date,
                                                android.text.format.Formatter.formatShortFileSize(ctx, detail.sizeBytes),
                                                "${android.text.format.DateFormat.getDateFormat(ctx).format(java.util.Date(detail.modifiedAt))} ${android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(detail.modifiedAt))}"),
                                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (active) Text(stringResource(R.string.setup_log_current), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SecondaryButton(stringResource(R.string.setup_log_share), enabled = com.droidhost.launcher.session.SessionLogShare.progress == null, compact = true, modifier = Modifier.weight(1f)) { a.onShareSessionLog(folder) }
                                        SecondaryButton(stringResource(R.string.setup_log_save), enabled = com.droidhost.launcher.session.SessionLogShare.progress == null, compact = true, modifier = Modifier.weight(1f)) { a.onSaveSessionLog(folder) }
                                        SecondaryButton(stringResource(R.string.setup_log_delete), compact = true, enabled = !active, modifier = Modifier.weight(1f)) { deleteSessionLogs = folder }
                                    }
                                }
                                androidx.compose.material3.HorizontalDivider()
                            }
                            ActionRow(stringResource(R.string.setup_saved_logs), stringResource(R.string.setup_saved_logs_hint, com.droidhost.launcher.session.SessionPaths.KEEP_SESSIONS), stringResource(R.string.setup_clear_logs), a.onClearLogs)
                            ActionRow(stringResource(R.string.setup_reset_settings), stringResource(R.string.setup_reset_settings_hint),
                                stringResource(R.string.setup_reset_settings_button), a.onResetSettings)
                        }
                        SettingsGroup(stringResource(R.string.setup_group_container)) {
                            GameEnvironmentRow(
                                initialScope = "",
                                label = stringResource(R.string.setup_container_env),
                                hint = stringResource(R.string.setup_container_env_hint),
                                restartTag = true,
                            )
                            val allFilesMissing = com.droidhost.launcher.files.AllFilesAccess.missing()
                            SettingsRow(stringResource(R.string.setup_all_files),
                                stringResource(if (allFilesMissing) R.string.setup_all_files_hint else R.string.setup_all_files_granted)) {
                                SecondaryButton(stringResource(R.string.upd_open_settings)) { com.droidhost.launcher.files.AllFilesAccess.open(ctx) }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteSessionLogs?.let { folder ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteSessionLogs = null },
            title = { Text(stringResource(R.string.setup_log_delete_title)) },
            text = { Text(stringResource(R.string.setup_log_delete_message, folder.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    deleteSessionLogs = null
                    a.onDeleteSessionLog(folder) { logsRefresh++ }
                }) { Text(stringResource(R.string.setup_log_delete)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleteSessionLogs = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
internal fun ReadinessCard(
    passing: Int, total: Int, line: String, open: Boolean, onDetails: () -> Unit, title: String? = null, ready: Boolean = passing == total,
    checks: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val tint = if (ready) pal.good else AttentionAmber
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { passing / total.toFloat().coerceAtLeast(1f) }, strokeWidth = 4.dp,
                    color = tint, trackColor = tint.copy(alpha = 0.18f), modifier = Modifier.size(52.dp),
                )
                Text("$passing/$total", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title ?: stringResource(if (ready) R.string.setup_ready else R.string.setup_not_ready),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                )
                if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (passing > 0) SecondaryButton(stringResource(if (open) R.string.common_hide else R.string.setup_details), compact = true, onClick = onDetails)
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                checks()
            }
        }
    }
}

@Composable
private fun FrameGenMenu(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    FrameGenMenu(host, s.frameGen, s.lossless, a.onFrameGenPick, a.onImportLossless)
}

@Composable
internal fun LaunchSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost, game: com.droidhost.launcher.frontend.Library.SteamGame? = null, hopRail: String = "games") {
    val columns = if (LocalNarrowPane.current) 2 else 3
    val controller = a.controller
    val context = androidx.compose.ui.platform.LocalContext.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    val wincompKey = game?.takeIf { it.library == com.droidhost.launcher.frontend.Library.ADDED || it.appId > 0 }?.profileKey
    val frameGenProfileKey = game?.profileKey?.takeIf { it.isNotBlank() }
    var gameFrameGenMode by remember(frameGenProfileKey) {
        mutableStateOf(frameGenProfileKey?.let {
            com.droidhost.launcher.gpu.FrameGen.mode(context, it)
        } ?: s.frameGen)
    }
    var gameFrameGenOverride by remember(frameGenProfileKey) {
        mutableStateOf(frameGenProfileKey?.let {
            com.droidhost.launcher.gpu.FrameGen.hasGameOverride(context, it)
        } ?: false)
    }
    var wincompOpen by remember(wincompKey) { mutableStateOf<Boolean?>(null) }
    val wincompPicks = remember(wincompKey, wincompOpen) { wincompKey?.let { com.droidhost.launcher.session.WinComponents.picks(context, it) }.orEmpty() }
    val steamScope = game?.let { com.droidhost.launcher.session.NoSteamLaunch.scope(it) }
    val steamCard: (@Composable (Modifier) -> Unit)? = if (game != null && steamScope != null) { m ->
        Box(m) { GameSettingsCard(host, game, steamScope) }
    } else null
    val cards = if (game?.library == com.droidhost.launcher.frontend.Library.EPIC_LAUNCHER) listOfNotNull(steamCard) else buildList<@Composable (Modifier) -> Unit> {
        add { m ->
            val back = stringResource(R.string.hop_from_games)
            SettingCard(stringResource(R.string.setup_card_components), stringResource(R.string.setup_card_components_hint), "card:components", m.hopSource("card:components")) {
                val at = Hops.sources["card:components"]
                val open = { if (game == null) a.onComponents(true) else a.onGameComponents(game) }
                if (at == null) open()
                else Hops.go(HopOrigin(hopRail, HOP_SURFACE_GAMES, null, "card:components", at, back, dest = "components", page = "components"), open)
            }
        }
        add { m ->
            Box(m) {
                SettingCard(
                    stringResource(R.string.frame_gen_title),
                    if (frameGenProfileKey != null)
                        com.droidhost.launcher.gpu.FrameGen.label(context, gameFrameGenMode)
                    else s.frameGenLabel,
                    "card:fg", Modifier.fillMaxSize()
                ) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(
                    host = host,
                    mode = if (frameGenProfileKey != null) gameFrameGenMode else s.frameGen,
                    lossless = s.lossless,
                    onPick = { mode ->
                        val key = frameGenProfileKey
                        if (key == null) {
                            a.onFrameGenPick(mode)
                        } else {
                            com.droidhost.launcher.gpu.FrameGen.setForGame(context, key, mode)
                            gameFrameGenMode = mode
                            gameFrameGenOverride = true
                        }
                    },
                    onImportLossless = a.onImportLossless,
                    gameProfileId = frameGenProfileKey,
                    hasGameOverride = gameFrameGenOverride,
                    onResetGame = {
                        val key = frameGenProfileKey
                        if (key != null) {
                            com.droidhost.launcher.gpu.FrameGen.clearGame(context, key)
                            gameFrameGenMode =
                                com.droidhost.launcher.gpu.FrameGen.mode(context)
                            gameFrameGenOverride = false
                        }
                    }
                )
            }
        }
        if (controller != null) add { m -> SettingCard(stringResource(R.string.setup_card_controls), stringResource(R.string.setup_card_controls_hint), "card:controls", m, onClick = controller.onMapping) }
        if (steamCard != null) add(steamCard)
        val cardStore = com.droidhost.launcher.stores.Store.byId(game?.source.orEmpty())?.takeIf { it == com.droidhost.launcher.stores.Store.EPIC || it == com.droidhost.launcher.stores.Store.GOG }
        if (cardStore != null && game?.gameFiles != null) add { m ->
            Box(m) {
                StoreLaunchCard(host, game.gameFiles!!, cardStore)
            }
        }
        if (wincompKey != null) add { m ->
            SettingCard(
                stringResource(R.string.wincomp_title),
                if (wincompPicks.isEmpty()) stringResource(R.string.wincomp_card_none) else wincompPicks.joinToString(", ") { com.droidhost.launcher.session.WinComponentNames.of(it) },
                "card:wincomp", m,
            ) { wincompOpen = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard }
        }
    }
    val opened = wincompOpen
    if (opened != null && wincompKey != null && game != null) WinComponentsDialog(
        wincompKey, game.name, game.gameFiles, opened, compat = game.protonPrefix,
        steamAppId = game.appId.takeIf { game.library != com.droidhost.launcher.frontend.Library.ADDED },
        game = game,
    ) { wincompOpen = null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        for (row in cards.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (card in row) card(Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

internal class GameSettingsState(private val appContext: android.content.Context, val scope: String) {
    var config by mutableStateOf(runCatching { com.droidhost.launcher.session.GameEnvironmentStore.read(appContext) }.getOrNull())
    var saving by mutableStateOf(false)
    val noSteam: Boolean get() = config?.let { com.droidhost.launcher.core.GameEnvironment.gameNoSteam(it, scope) } == true

    fun save(change: (android.content.Context) -> com.droidhost.launcher.core.GameEnvironment.Config?) {
        if (saving) return
        saving = true
        Thread({
            val written = runCatching { change(appContext) }
                .onFailure { android.util.Log.w("GameSettingsCard", "could not save the settings of $scope: ${it.message}") }.getOrNull()
            val next = written ?: runCatching { com.droidhost.launcher.session.GameEnvironmentStore.read(appContext) }.getOrNull()
            com.droidhost.launcher.stores.StoresState.post {
                config = next
                saving = false
            }
        }, "game-settings").start()
    }
}

@Composable
internal fun rememberGameSettings(scope: String): GameSettingsState {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    return remember(scope) { GameSettingsState(appContext, scope) }
}

@Composable
private fun GameSettingsCard(host: MenuHost, game: com.droidhost.launcher.frontend.Library.SteamGame, scope: String) {
    val state = rememberGameSettings(scope)
    SettingCard(stringResource(R.string.game_settings_card), stringResource(if (state.noSteam) R.string.game_steam_without else R.string.game_steam_with), "card:$GAME_SETTINGS_MENU", Modifier.fillMaxSize()) {
        host.open = if (host.open == GAME_SETTINGS_MENU) null else GAME_SETTINGS_MENU
    }
    GameSettingsMenus(host, game, state)
}

@Composable
internal fun GameSettingsMenus(host: MenuHost, game: com.droidhost.launcher.frontend.Library.SteamGame, state: GameSettingsState) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val scope = state.scope
    val current = state.config
    val noSteam = state.noSteam
    val fex = current?.let { runCatching { com.droidhost.launcher.core.GameEnvironment.gameFexPreset(it, scope) }.getOrNull() }
    val sync = current?.let { runCatching { com.droidhost.launcher.core.GameEnvironment.gameSync(it, scope) }.getOrNull() }
    val ready = current != null && !state.saving
    val launcher = game.library == com.droidhost.launcher.frontend.Library.EPIC_LAUNCHER
    fun save(change: (android.content.Context) -> com.droidhost.launcher.core.GameEnvironment.Config?) = state.save(change)
    val key = GAME_SETTINGS_MENU
    val fexKey = "game-settings-fex"
    val syncKey = "game-settings-sync"
    val syncLabels = mapOf(
        com.droidhost.launcher.session.SessionPrefs.SYNC_NTSYNC to stringResource(R.string.sync_backend_ntsync),
        com.droidhost.launcher.session.SessionPrefs.SYNC_FSYNC to stringResource(R.string.sync_backend_fsync),
        com.droidhost.launcher.session.SessionPrefs.SYNC_ESYNC to stringResource(R.string.sync_backend_esync),
        com.droidhost.launcher.session.SessionPrefs.SYNC_WINESERVER to stringResource(R.string.sync_backend_wineserver),
    )
    val appSync = syncLabels[com.droidhost.launcher.session.SessionPrefs.syncBackend(appContext)]
    val useDefault = stringResource(R.string.comp_game_use_general)
    val generalFex = com.droidhost.launcher.core.FexPreset.byId(com.droidhost.launcher.session.SessionPrefs.fexPreset(appContext))
    val custom = stringResource(R.string.comp_game_fex_custom)
    val customSync = stringResource(R.string.game_sync_custom)
    val resetDone = stringResource(R.string.comp_game_reset_done, game.name)
    val resKey = "game-settings-res"
    val resolution = current?.let { com.droidhost.launcher.core.GameEnvironment.gameResolution(it, scope) }
    val screen = remember { com.droidhost.launcher.session.SessionDisplay.screenSize(com.droidhost.launcher.session.SessionDisplay.panelSize(appContext)) }
    val matchScreen = stringResource(R.string.display_match_screen, screen.first, screen.second)
    val scaledLabels = com.droidhost.launcher.core.GameResolution.scales.associateWith {
        stringResource(R.string.display_follow_screen_scaled, com.droidhost.launcher.core.GameResolution.formatScale(it))
    }
    fun sizeLabel(size: Pair<Int, Int>) = "${size.first}×${size.second} · ${com.droidhost.launcher.core.GameResolution.aspectLabel(size.first, size.second)}"
    fun resolutionLabel(choice: com.droidhost.launcher.core.GameResolution.Choice?): String = when (choice) {
        null -> matchScreen
        is com.droidhost.launcher.core.GameResolution.Scaled -> (scaledLabels[choice.scale]
            ?: "÷" + com.droidhost.launcher.core.GameResolution.formatScale(choice.scale)) +
            " · " + sizeLabel(com.droidhost.launcher.core.GameResolution.resolve(choice, screen))
        is com.droidhost.launcher.core.GameResolution.Fixed -> sizeLabel(choice.width to choice.height)
    }
    fun pickResolution(choice: com.droidhost.launcher.core.GameResolution.Choice?) {
        host.open = key
        save { com.droidhost.launcher.session.GameEnvironmentStore.setGameResolution(it, scope, choice) }
    }
    var editResolution by remember(scope) { mutableStateOf(false) }
    AnchoredMenu(host.open == key, onDismiss = { if (host.open == key) host.open = null }, title = stringResource(R.string.game_settings_menu_title, game.name)) { first ->
        MenuItem(stringResource(R.string.game_no_steam_title), checked = noSteam, enabled = ready,
            detail = stringResource(R.string.game_no_steam_detail), focusRequester = first) {
            val on = !noSteam
            save { com.droidhost.launcher.session.GameEnvironmentStore.setGameNoSteam(it, scope, on) }
        }
        if (noSteam) MenuItem(
            stringResource(R.string.game_res_title), checked = false, enabled = ready,
            detail = resolutionLabel(resolution),
            trailing = { Text("›", fontSize = 16.sp, color = colors.onSurfaceVariant) },
        ) { host.open = resKey }
        if (!launcher) {
            val chosen = com.droidhost.launcher.core.FexPreset.all.firstOrNull { it.id == fex }
            MenuItem(
                stringResource(R.string.fex_preset_title), checked = false, enabled = ready,
                detail = when {
                    fex == null -> "$useDefault · ${stringResource(generalFex.label)}"
                    chosen == null -> custom
                    else -> stringResource(chosen.label)
                },
                trailing = { Text("›", fontSize = 16.sp, color = colors.onSurfaceVariant) },
            ) { host.open = fexKey }
            MenuItem(
                stringResource(R.string.sync_backend_title), checked = false, enabled = ready,
                detail = when (sync) {
                    null -> useDefault
                    com.droidhost.launcher.core.GameEnvironment.CUSTOM_SYNC -> customSync
                    else -> syncLabels[sync]
                },
                trailing = { Text("›", fontSize = 16.sp, color = colors.onSurfaceVariant) },
            ) { host.open = syncKey }
        }
        MenuItem(stringResource(R.string.game_settings_default), checked = false, enabled = ready,
            detail = stringResource(R.string.game_settings_default_detail)) {
            save {
                val failure = runCatching { com.droidhost.launcher.session.GameProfileManager.reset(it, scope) }.exceptionOrNull()
                com.droidhost.launcher.stores.StoresState.post {
                    android.widget.Toast.makeText(
                        it,
                        if (failure == null) resetDone else it.getString(R.string.comp_game_reset_failed, failure.message.orEmpty()),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
                null
            }
        }
    }
    AnchoredMenu(host.open == resKey, onDismiss = { if (host.open == resKey) host.open = null }, title = stringResource(R.string.game_res_title)) { first ->
        MenuItem(matchScreen, checked = resolution == null, enabled = ready, focusRequester = first) { pickResolution(null) }
        com.droidhost.launcher.core.GameResolution.scales.forEach { scale ->
            val choice = com.droidhost.launcher.core.GameResolution.Scaled(scale)
            MenuItem(scaledLabels.getValue(scale), checked = resolution == choice, enabled = ready,
                detail = sizeLabel(com.droidhost.launcher.core.GameResolution.resolve(choice, screen))) { pickResolution(choice) }
        }
        com.droidhost.launcher.core.GameResolution.aspects.forEach { aspect ->
            val sizes = com.droidhost.launcher.core.GameResolution.sizesFor(aspect, screen)
            MenuItem(aspect.label, checked = resolution in aspect.sizes, enabled = ready,
                detail = sizes.joinToString(" · ") { "${it.width}×${it.height}" },
                trailing = { Text("›", fontSize = 16.sp, color = colors.onSurfaceVariant) },
            ) { host.open = "$resKey-${aspect.label}" }
        }
        val fixed = resolution as? com.droidhost.launcher.core.GameResolution.Fixed
        val customValue = fixed?.takeIf { f -> com.droidhost.launcher.core.GameResolution.aspects.none { f in it.sizes } }
        MenuItem(
            if (customValue == null) stringResource(R.string.mode_res_custom) else stringResource(R.string.mode_res_custom_value, customValue.width, customValue.height),
            checked = customValue != null, enabled = ready,
            detail = customValue?.let { com.droidhost.launcher.core.GameResolution.aspectLabel(it.width, it.height) },
        ) { editResolution = true }
    }
    com.droidhost.launcher.core.GameResolution.aspects.forEach { aspect ->
        val aspectKey = "$resKey-${aspect.label}"
        AnchoredMenu(host.open == aspectKey, onDismiss = { if (host.open == aspectKey) host.open = null }, title = aspect.label) { first ->
            com.droidhost.launcher.core.GameResolution.sizesFor(aspect, screen).forEachIndexed { index, size ->
                MenuItem("${size.width}×${size.height}", checked = resolution == size, enabled = ready,
                    focusRequester = if (index == 0) first else null) { pickResolution(size) }
            }
        }
    }
    if (editResolution) CustomResolutionDialog(
        (resolution as? com.droidhost.launcher.core.GameResolution.Fixed)?.let { it.width to it.height },
        onSave = { value ->
            editResolution = false
            pickResolution(com.droidhost.launcher.core.GameResolution.Fixed(value.first, value.second))
        },
        onDismiss = { editResolution = false },
    )
    AnchoredMenu(host.open == fexKey, onDismiss = { if (host.open == fexKey) host.open = null }, title = stringResource(R.string.fex_preset_title)) { first ->
        MenuItem(useDefault, checked = fex == null, enabled = ready, detail = stringResource(generalFex.label), focusRequester = first) {
            host.open = key
            save { com.droidhost.launcher.session.GameEnvironmentStore.setGameFexPreset(it, scope, null) }
        }
        if (fex == com.droidhost.launcher.core.GameEnvironment.CUSTOM_FEX_PRESET) {
            MenuItem(custom, checked = true, enabled = false) {}
        }
        com.droidhost.launcher.core.FexPreset.all.forEach { option ->
            MenuItem(stringResource(option.label), checked = fex == option.id, enabled = ready, detail = stringResource(option.detail)) {
                host.open = key
                save { com.droidhost.launcher.session.GameEnvironmentStore.setGameFexPreset(it, scope, option.id) }
            }
        }
    }
    AnchoredMenu(host.open == syncKey, onDismiss = { if (host.open == syncKey) host.open = null }, title = stringResource(R.string.sync_backend_title)) { first ->
        MenuItem(useDefault, checked = sync == null, enabled = ready, detail = appSync, focusRequester = first) {
            host.open = key
            save { com.droidhost.launcher.session.GameEnvironmentStore.setGameSync(it, scope, null) }
        }
        if (sync == com.droidhost.launcher.core.GameEnvironment.CUSTOM_SYNC) {
            MenuItem(customSync, checked = true, enabled = false) {}
        }
        com.droidhost.launcher.core.GameEnvironment.syncBackends.forEach { option ->
            MenuItem(syncLabels.getValue(option), checked = sync == option, enabled = ready) {
                host.open = key
                save { com.droidhost.launcher.session.GameEnvironmentStore.setGameSync(it, scope, option) }
            }
        }
    }
}

internal const val GAME_SETTINGS_MENU = "game-settings"

private const val EPIC_RESOLVE_URL = "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Faccount%2Fpersonal"

@Composable
private fun StoreLaunchCard(host: MenuHost, folder: java.io.File, store: com.droidhost.launcher.stores.Store) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var sidecar by remember(folder) { mutableStateOf(com.droidhost.launcher.stores.StoreGameSidecar.read(folder)) }
    val options = sidecar?.epic ?: com.droidhost.launcher.stores.EpicOptions()
    val epic = store == com.droidhost.launcher.stores.Store.EPIC
    val key = "store-" + store.id
    val value = if (!epic) stringResource(when {
        !com.droidhost.launcher.stores.gog.GogComet.enabled(appContext) || sidecar?.achievements == false -> R.string.gog_achievements_off
        com.droidhost.launcher.stores.gog.GogComet.status == "sign-in-needed" -> R.string.gog_achievements_signin
        com.droidhost.launcher.stores.gog.GogComet.status in listOf("service-unavailable", "credentials-unavailable", "not-installed") -> R.string.gog_achievements_unavailable
        com.droidhost.launcher.stores.gog.GogComet.status == "uploaded" -> R.string.gog_achievements_uploaded
        com.droidhost.launcher.stores.gog.GogComet.status == "pending-upload" || com.droidhost.launcher.stores.gog.GogComet.status == "unlocked-locally" -> R.string.gog_achievements_pending
        else -> R.string.gog_achievements_on
    }) else buildList {
        if (epic && options.offline) add(stringResource(R.string.epic_card_offline))
        else if (epic && options.eos) add(stringResource(R.string.epic_card_eos))
    }.ifEmpty { listOf(stringResource(R.string.epic_card_none)) }.joinToString(" · ")
    fun set(change: (com.droidhost.launcher.stores.EpicOptions) -> com.droidhost.launcher.stores.EpicOptions) {
        Thread({
            val written = runCatching { com.droidhost.launcher.stores.StoreGameSidecar.updateEpic(folder, change) }
                .onFailure { android.util.Log.w("EpicLaunchCard", "could not write ${folder.name}: ${it.message}") }.getOrNull()
            if (written != null) android.util.Log.i("EpicLaunchCard", "epic options ${written.id} eos=${written.epic.eos} offline=${written.epic.offline}")
            else android.util.Log.w("EpicLaunchCard", "epic options not saved for ${folder.name}")
            com.droidhost.launcher.stores.StoresState.post { sidecar = written ?: com.droidhost.launcher.stores.StoreGameSidecar.read(folder) }
        }, "epic-options").start()
    }
    SettingCard(store.shortLabel, value, "card:$key", Modifier.fillMaxSize()) {
        host.open = if (host.open == key) null else key
    }
    AnchoredMenu(host.open == key, onDismiss = { if (host.open == key) host.open = null }, title = store.shortLabel) { first ->
        if (!epic) {
            MenuItem(stringResource(R.string.gog_achievements), checked = sidecar?.achievements != false, focusRequester = first,
                detail = if (!com.droidhost.launcher.stores.gog.GogComet.enabled(appContext)) stringResource(R.string.gog_achievements_enable_store) else null) {
                val on = sidecar?.achievements == false
                Thread({
                    val written = runCatching { com.droidhost.launcher.stores.StoreGameSidecar.updateAchievements(folder, on) }.getOrNull()
                    com.droidhost.launcher.stores.StoresState.post { sidecar = written ?: sidecar }
                }, "gog-game-achievements").start()
            }
        }
        if (epic) {
            MenuItem(stringResource(R.string.epic_eos), checked = options.eos, focusRequester = first) { set { it.copy(eos = !it.eos) } }
            MenuItem(stringResource(R.string.epic_offline), checked = options.offline) { set { it.copy(offline = !it.offline) } }
        }
        if (epic) {
        MenuItem(stringResource(R.string.epic_resolve), checked = false) {
            host.open = null
            runCatching {
                appContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(EPIC_RESOLVE_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        }
    }
}

@Composable
internal fun SettingCard(label: String, value: String, id: String, modifier: Modifier, badge: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.paneItem(id)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (LocalGlass.current != null) Modifier.glass(Shape14, if (hot) 0.04f else 0f) else Modifier.clip(Shape14).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface))
            .glideBorder(hot, Shape14, pal.signal, if (LocalGlass.current != null) Color.Transparent else pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, fontSize = 13.sp, color = if (hot) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (badge != null) NeedsRestartTag(badge)
        }
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LanguageRow(host: MenuHost, chosen: String, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemName = AppLanguage.displayName(AppLanguage.system(context))
    val systemLabel = stringResource(R.string.setup_language_system, systemName)
    SettingsRow(stringResource(R.string.setup_language), stringResource(R.string.setup_language_hint)) {
        Box {
            ValueChip(if (chosen == AppLanguage.SYSTEM) stringResource(R.string.setup_language_system_short) else AppLanguage.nativeName(chosen),
                host.open == "language") { host.open = if (host.open == "language") null else "language" }
            AnchoredMenu(host.open == "language", onDismiss = { if (host.open == "language") host.open = null }, title = stringResource(R.string.setup_language)) { firstItemFocus ->
                MenuItem(systemLabel, checked = chosen == AppLanguage.SYSTEM, focusRequester = firstItemFocus) {
                    host.open = null
                    onPick(AppLanguage.SYSTEM)
                }
                AppLanguage.supported.forEach { tag ->
                    MenuItem(AppLanguage.nativeName(tag), checked = chosen == tag) {
                        host.open = null
                        onPick(tag)
                    }
                }
            }
        }
    }
}

@Composable
private fun RgbSpeedRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    SliderRow(stringResource(R.string.theme_rgb_speed), stringResource(R.string.theme_rgb_speed_hint), Themes.rgbSpeed, 1..Themes.RGB_SPEEDS) { Themes.setRgbSpeed(ctx, it) }
}

@Composable
private fun GradientColorRows() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val (primary, secondary) = Themes.gradientColors
    var picking by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val primaryLabel = stringResource(R.string.theme_gradient_primary)
    val secondaryLabel = stringResource(R.string.theme_gradient_secondary)
    SettingsRow(primaryLabel, null) { ColorCube(androidx.compose.ui.graphics.Color(primary), primaryLabel) { picking = 1 } }
    SettingsRow(secondaryLabel, null) { ColorCube(androidx.compose.ui.graphics.Color(secondary), secondaryLabel) { picking = 2 } }
    if (picking != 0) ColorPickerDialog(
        if (picking == 1) primaryLabel else secondaryLabel, if (picking == 1) primary else secondary,
        onDismiss = { picking = 0 },
    ) { argb ->
        if (picking == 1) Themes.setGradient(ctx, argb, secondary) else Themes.setGradient(ctx, primary, argb)
    }
}
