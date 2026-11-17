package com.droidhost.launcher.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droidhost.launcher.R
import com.droidhost.launcher.core.FileUtils
import com.droidhost.launcher.files.FileOps
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.session.SessionService
import com.droidhost.launcher.session.SessionState
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmDesktop
import com.droidhost.launcher.vm.VmDriver
import com.droidhost.launcher.vm.VmMachine
import com.droidhost.launcher.vm.VmPreset
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val VM_TABS = modeSheetTabs(steam = false) + SheetTab.FILES

@Composable
internal fun DesktopPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val narrow = LocalNarrowPane.current
    val short = compactDesktop()
    val colors = MaterialTheme.colorScheme
    val mode = SessionService.MODE_DESKTOP
    val tabs = VM_TABS.map { it to sheetTabLabel(it) }
    fun openFrom(at: Rect?, art: Any?, page: String) {
        at?.let { PageOrigin.mark(Origin(it, 16f * density.density, art = art)) }
        onSelect(page)
    }

    // Several machines coexist; the page acts on the one the user selected.
    var recorded by remember { mutableStateOf(VmStore.all(ctx)) }
    // A build that already carries the desktop predates any record; it stands in as one machine.
    val machines = if (recorded.isEmpty() && s.desktopPresent)
        listOfNotNull(VmCatalog.of(VmCatalog.UBUNTU)?.let { VmMachine(it, 0L) }) else recorded
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val vm = machines.firstOrNull { it.id == selectedId } ?: machines.firstOrNull()

    var install by remember { mutableStateOf(VmDesktop.state()) }
    var seed by remember { mutableStateOf(VmPreset.state()) }
    LaunchedEffect(install.busy, seed.busy) {
        while (install.busy || seed.busy) {
            delay(1000)
            install = VmDesktop.state()
            seed = VmPreset.state()
        }
        // The rootfs a finished install or seed left behind changes what the cards say.
        recorded = VmStore.all(ctx)
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }

    // The machine the running session is for; without a named one the first stands in.
    val runningMachineId = SessionState.machineId ?: VmStore.load(ctx)?.id
    fun ownRootfs(m: VmMachine?) = m?.let { VmRuntime.ownRootfs(ctx, it.id) }
    fun statusOf(m: VmMachine): VmStatus {
        val own = ownRootfs(m)
        val installingHere = (install.busy && install.machineId == m.id) || (seed.busy && seed.machineId == m.id)
        return vmStatus(
            s, m, VmDesktop.desktopInstalled(own), own != null, VmDriver.driverInstalled(own),
            s.sessionRunning && runningMachineId == m.id, installingHere,
        )
    }
    fun percentOf(m: VmMachine): Int? = when {
        install.busy && install.machineId == m.id -> install.percent
        seed.busy && seed.machineId == m.id -> seed.percent
        s.busy && ownRootfs(m) == null -> s.percent
        else -> null
    }

    val status = vm?.let { statusOf(it) } ?: VmStatus.ABSENT
    val vmHasOwnRootfs = ownRootfs(vm) != null
    val primary: () -> Unit = when {
        status == VmStatus.ABSENT -> ({ picking = true })
        status == VmStatus.RUNNING -> a.onVmStop
        status == VmStatus.READY || status == VmStatus.DEGRADED -> ({ a.onDesktop(vm?.id) })
        // A machine with its own rootfs but no desktop yet gets one fitted in; the shared runtime
        // installs its desktop through the session itself, as it always has.
        status == VmStatus.IMPORTED || (status == VmStatus.NEW && vmHasOwnRootfs) -> ({
            vm?.let { if (VmDesktop.start(ctx, it)) install = VmDesktop.state() }
        })
        status == VmStatus.NEW -> ({ a.onDesktop(vm?.id) })
        else -> ({})
    }
    val primaryEnabled = when (status) {
        VmStatus.ABSENT -> !s.busy
        VmStatus.NEW -> !s.busy && !s.runtimeActionsBlocked && !install.busy && !seed.busy
        VmStatus.READY, VmStatus.DEGRADED, VmStatus.RUNNING -> !s.busy
        VmStatus.IMPORTED -> !s.busy && !install.busy && !seed.busy && !s.runtimeActionsBlocked
        else -> false
    }
    val label = vmPrimaryLabel(status)
    val head = vm?.let { vmMachineLabel(ctx, it) } ?: stringResource(R.string.vm_title)
    val note = when {
        seed.busy && seed.machineId == vm?.id -> stringResource(R.string.vm_preset_seeding_running, seed.percent)
        seed.stage == VmPreset.Stage.FAILED && seed.machineId == vm?.id ->
            stringResource(R.string.vm_preset_failed, vmSeedReason(seed.reason))
        install.busy && install.machineId == vm?.id -> stringResource(R.string.vm_install_desktop_running, install.percent)
        install.stage == VmDesktop.Stage.FAILED && install.machineId == vm?.id ->
            stringResource(R.string.vm_install_desktop_failed, vmInstallReason(install.reason))
        status == VmStatus.DEGRADED -> stringResource(R.string.vm_degraded_note)
        status == VmStatus.IMPORTED -> stringResource(R.string.vm_imported_note)
        else -> null
    }

    LiftHero(
        key = "desktop", tabs = tabs,
        onOpen = a.onDesktopSettings, onPrimary = primary,
        modifier = modifier.clipToBounds(),
        start = if (narrow) 20.dp else 44.dp, bottom = 44.dp,
        background = { DesktopWall(emptyList()) },
        scrim = { lifted ->
            WallFade()
            Box(Modifier.graphicsLayer { val v = lifted(); alpha = 1f - v; translationY = -12.dp.toPx() * v }) {
                VmDock(
                    s, machines, vm?.id,
                    statusOf = { statusOf(it) }, percentOf = { percentOf(it) },
                    onSelect = { selectedId = it.id }, onNew = { picking = true },
                )
            }
        },
        words = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (LocalConfiguration.current.screenHeightDp >= 400) Text(
                    head, fontSize = if (short) 30.sp else 42.sp, lineHeight = if (short) 34.sp else 46.sp,
                    fontWeight = FontWeight.Black, color = colors.onBackground,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VmStateChip(status)
                    BusyChip(s)
                }
                if (note != null) Text(
                    note, fontSize = 12.5.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                )
            }
        },
        button = { click ->
            PrimaryButton(label, enabled = primaryEnabled, main = true, large = true, icon = vmPrimaryIcon(status), onClick = click)
        },
        beside = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                HeroIconButton(Icons.Outlined.Storefront, stringResource(R.string.flathub_name), FlathubTeal, "vm:store") { at -> openFrom(at, null, "store") }
                HeroIconButton(Icons.Outlined.Folder, stringResource(R.string.setup_tool_files), null, "vm:files") { a.onFiles() }
                if (vm != null) HeroIconButton(Icons.Outlined.Settings, stringResource(R.string.vm_settings), null, "vm:settings") { settings = true }
                // A machine with its own rootfs is deleted whole; the shared runtime only loses its
                // desktop, the system image stays. Two different deletions, two different wordings.
                if (vm != null && vmHasOwnRootfs) VmRemoveMachine(
                    label = stringResource(R.string.vm_delete_machine),
                    title = stringResource(R.string.vm_delete_machine_title),
                    note = stringResource(R.string.vm_delete_machine_text),
                    enabled = !s.busy && !s.runtimeActionsBlocked,
                    onRemove = {
                        VmStore.remove(ctx, vm)
                        recorded = VmStore.all(ctx)
                        selectedId = null
                    },
                )
                else if (vm != null) UninstallDesktop(
                    label = stringResource(R.string.vm_delete), title = stringResource(R.string.vm_delete_title),
                    note = stringResource(R.string.vm_delete_text),
                    enabled = !s.busy && !s.runtimeActionsBlocked, onUninstall = a.onRemoveDesktop,
                )
            }
        },
        rows = { tab ->
            if (tab == SheetTab.FILES) DesktopFiles(s, a)
            else a.modeSheet(mode, tab, SheetExtras())
        },
    )
    if (picking) VmSourceDialog(onDismiss = { picking = false }) { created, start ->
        picking = false
        recorded = VmStore.all(ctx)
        selectedId = created.id
        // A preset machine starts seeding inside the wizard; take its state now so the progress
        // poll below picks it up.
        seed = VmPreset.state()
        install = VmDesktop.state()
        if (start) a.onDesktop(created.id)
    }
    if (settings && vm != null) VmSettingsDialog(
        machine = vm,
        driverInstalled = VmDriver.driverInstalled(ownRootfs(vm)),
        hasOwnRootfs = vmHasOwnRootfs,
        onDismiss = { settings = false },
        onRemoveMachine = {
            VmStore.remove(ctx, vm)
            recorded = VmStore.all(ctx)
            selectedId = null
        },
        onRemoveDesktop = a.onRemoveDesktop,
    )
}

/**
 * What one machine is made of: the driver it runs on, the disk it uses against a soft ceiling, and
 * the deletion that fits it. A machine with its own rootfs is deleted whole; the shared runtime
 * only loses its desktop.
 */
@Composable
private fun VmSettingsDialog(
    machine: VmMachine,
    driverInstalled: Boolean,
    hasOwnRootfs: Boolean,
    onDismiss: () -> Unit,
    onRemoveMachine: () -> Unit,
    onRemoveDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val root = if (hasOwnRootfs) VmRuntime.ownRootfs(ctx, machine.id) else LinuxRuntime.rootDir(ctx)
    val size by produceState<Long?>(null, root) {
        value = withContext(Dispatchers.IO) { runCatching { FileOps.totalSize(root) }.getOrNull() }
    }
    var cap by remember { mutableStateOf(VmStore.capBytes(ctx, machine.id)) }
    val caps = listOf(2L, 4L, 8L, 16L, 0L).map { it * 1024 * 1024 * 1024 }
    val used = size ?: 0L
    val fraction = if (cap > 0) (used.toFloat() / cap).coerceIn(0f, 1f) else 0f
    val over = cap > 0 && used > cap
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vm_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(vmMachineLabel(ctx, machine), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text(
                    stringResource(R.string.vm_settings_driver), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
                // The shared runtime is the app's patched build, so it always carries the driver.
                Text(
                    stringResource(if (driverInstalled || !hasOwnRootfs) R.string.vm_settings_driver_gpu else R.string.vm_settings_driver_software),
                    fontSize = 13.sp, color = colors.onBackground,
                )
                Text(
                    stringResource(R.string.vm_settings_storage), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
                Text(
                    stringResource(R.string.vm_settings_usage, FileUtils.sizeToString(used)),
                    fontSize = 13.sp, color = colors.onBackground,
                )
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (over) colors.error else colors.primary))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp),
                ) {
                    for (option in caps) SecondaryButton(
                        if (option == 0L) stringResource(R.string.vm_settings_cap_none) else FileUtils.sizeToString(option),
                        compact = true,
                    ) { cap = option; VmStore.setCapBytes(ctx, machine.id, option) }
                }
                Text(
                    stringResource(if (over) R.string.vm_settings_cap_over else R.string.vm_settings_cap_note),
                    fontSize = 12.sp, lineHeight = 17.sp,
                    color = if (over) colors.error else colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.vm_settings_danger), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp, color = colors.error, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                if (hasOwnRootfs) VmRemoveMachine(
                    label = stringResource(R.string.vm_delete_machine),
                    title = stringResource(R.string.vm_delete_machine_title),
                    note = stringResource(R.string.vm_delete_machine_text),
                    enabled = true,
                    onRemove = { onRemoveMachine(); onDismiss() },
                )
                else UninstallDesktop(
                    label = stringResource(R.string.vm_delete), title = stringResource(R.string.vm_delete_title),
                    note = stringResource(R.string.vm_delete_text),
                    enabled = true, onUninstall = { onRemoveDesktop(); onDismiss() },
                )
            }
        },
        confirmButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun compactDesktop() = LocalNarrowPane.current || LocalConfiguration.current.screenHeightDp < 600

@Composable
private fun HeroIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, tint: Color?, id: String, onClick: (Rect?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else if (hot) 1.04f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "heroIcon")
    val shape = RoundedCornerShape(12.dp)
    val accent = tint ?: pal.signal
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(56.dp).paneItem("btn:$id").onGloballyPositioned { at = it.boundsInRoot() }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) accent.copy(alpha = 0.18f) else colors.surface.copy(alpha = 0.92f))
            .glideBorder(hot, shape, accent, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onClick(at) }
            .controllerConfirm { onClick(at) }
            .semantics { contentDescription = description },
    ) { Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun DesktopWall(icons: List<Any>) {
    val pal = LocalPalette.current
    val ground = remember(pal) { Brush.linearGradient(listOf(pal.surfaceVariant, pal.surface)) }
    TiltedWall(icons.size, if (icons.isEmpty()) 75_000 else 50_000, capW = 132.dp, capH = 84.dp, gap = 14.dp, corner = 8.dp) { i ->
        WallCapsule(ground, edge = pal.line2, icon = icons.getOrNull(i), bar = pal.background.copy(alpha = 0.7f))
    }
}

@Composable
internal fun DesktopCard(
    key: String, name: String, detail: String, art: Any?,
    fallback: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Apps,
    first: Boolean = false, badge: String? = null, progress: Int? = null, accent: Boolean = false, tint: Color? = null,
    onClick: (Rect?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (hot) 1.03f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "deskCard")
    val shape = RoundedCornerShape(16.dp)
    var artAt by remember { mutableStateOf<Rect?>(null) }
    val width = if (LocalNarrowPane.current) 116.dp else 132.dp
    val artH = width * 0.62f
    val ground = tint?.copy(alpha = 0.25f) ?: if (accent) pal.signal.copy(alpha = 0.12f) else colors.surfaceVariant
    Column(
        Modifier.width(width).paneItem("tile:desktop:$key").then(if (first) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onClick(artAt) }
            .controllerConfirm { onClick(artAt) }
            .semantics { contentDescription = name },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(artH).onGloballyPositioned { artAt = it.boundsInRoot() }
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(ground),
        ) {
            when (art) {
                is Int -> {
                    Image(
                        painterResource(art), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.5f }
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    Image(painterResource(art), null, modifier = Modifier.size(artH * 0.6f))
                }
                null -> Icon(fallback, null, tint = tint ?: if (accent) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(artH * 0.42f))
                else -> {
                    AsyncImage(
                        model = art, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.8f; scaleY = 1.8f; alpha = 0.5f }
                            .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                    AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(artH * 0.6f))
                }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (progress != null) Box(Modifier.clip(RoundedCornerShape(99.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(4.dp)) {
                    if (progress >= 0) CircularProgressIndicator(progress = { progress / 100f }, strokeWidth = 2.dp, color = pal.signal, trackColor = pal.signal.copy(alpha = 0.25f), modifier = Modifier.size(16.dp))
                    else CircularProgressIndicator(strokeWidth = 2.dp, color = pal.signal, modifier = Modifier.size(16.dp))
                }
                else if (badge != null) Text(
                    badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(pal.signal).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Text(name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 12.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DesktopFiles(s: FrontEndState, a: FrontEndActions) {
    val ctx = LocalContext.current
    SettingsGroup(stringResource(R.string.setup_tool_files)) {
        SettingsRow(stringResource(R.string.setup_tool_files), stringResource(R.string.setup_tool_files_hint)) {
            SecondaryButton(stringResource(R.string.store_open), compact = true, onClick = a.onFiles)
        }
        SettingsRow(stringResource(R.string.setup_tool_roms), s.romsDir ?: stringResource(R.string.setup_tool_roms_hint)) {
            SecondaryButton(stringResource(R.string.setup_choose), compact = true, onClick = a.onRoms)
        }
    }
    if (s.desktopPresent) SettingsGroup(stringResource(R.string.content_linux_desktop)) {
        val size by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { runCatching { com.droidhost.launcher.runtime.DesktopCatalog.desktopSize(ctx) }.getOrNull() } }
        SettingsRow(stringResource(R.string.desktop_size), size?.let { FileUtils.sizeToString(it) } ?: "…") {}
        SettingsRow(stringResource(R.string.vm_delete), stringResource(R.string.vm_delete_text)) {
            UninstallDesktop(
                label = stringResource(R.string.vm_delete), title = stringResource(R.string.vm_delete_title),
                note = stringResource(R.string.vm_delete_text),
                enabled = !s.runtimeActionsBlocked, onUninstall = a.onRemoveDesktop,
            )
        }
    }
}

@Composable
private fun UninstallDesktop(label: String, title: String, note: String, enabled: Boolean, onUninstall: () -> Unit) {
    val canUninstall by rememberUpdatedState(enabled)
    val colors = MaterialTheme.colorScheme
    val keep = stringResource(R.string.flathub_keep)
    var at by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled, compact = true) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = colors.error, pillCorner = 12.dp, items = 3,
                handle = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(title)
                Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().stepItem(2)) {
                    StepChoice(keep, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open && canUninstall) { Steps.fold(); onUninstall() }
                }
            })
        }
    }
}
