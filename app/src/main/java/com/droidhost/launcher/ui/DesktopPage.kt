package com.droidhost.launcher.ui

import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droidhost.launcher.R
import com.droidhost.launcher.core.FileUtils
import com.droidhost.launcher.files.FileOps
import com.droidhost.launcher.files.InAppFilePicker
import com.droidhost.launcher.runtime.FlatpakHost
import com.droidhost.launcher.runtime.FlatpakManager
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.session.SessionService
import com.droidhost.launcher.session.SessionState
import com.droidhost.launcher.storage.StorageScan
import com.droidhost.launcher.store.StoreState
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmDesktop
import com.droidhost.launcher.vm.VmDriver
import com.droidhost.launcher.vm.VmExport
import com.droidhost.launcher.vm.VmLife
import com.droidhost.launcher.vm.VmLifecycle
import com.droidhost.launcher.vm.VmMachine
import com.droidhost.launcher.vm.VmPreset
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun DesktopPage(s: FrontEndState, a: FrontEndActions, modifier: Modifier, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val mode = SessionService.MODE_DESKTOP

    // Several machines coexist; the page acts on the one the user selected.
    var recorded by remember { mutableStateOf(VmStore.all(ctx)) }
    // A build that already carries the desktop predates any record; it stands in as one machine.
    val machines = if (recorded.isEmpty() && s.desktopPresent)
        listOfNotNull(VmCatalog.of(VmCatalog.UBUNTU)?.let { VmMachine(it, 0L) }) else recorded
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val vm = machines.firstOrNull { it.id == selectedId } ?: machines.firstOrNull()
    LaunchedEffect(vm?.id) { vm?.let { VmDesktop.backfillDesktopVersion(ctx, it) } }

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
    // A delete runs on its own thread; watch the record until the machine is gone.
    LaunchedEffect(recorded.any { VmLife.of(it.lifecycle) == VmLife.DELETING }) {
        while (recorded.any { VmLife.of(it.lifecycle) == VmLife.DELETING }) {
            delay(500)
            recorded = VmStore.all(ctx)
        }
    }
    // Installed apps change over in the store, so re-read them while this page is shown.
    var flatpakApps by remember { mutableStateOf(FlatpakManager.installedApps(ctx)) }
    LaunchedEffect(Unit) {
        while (true) { delay(3000); flatpakApps = FlatpakManager.installedApps(ctx) }
    }
    // Flatpak follows the selected machine. Mirror the selection into the flatpak host; a machine
    // without its own rootfs yet (seeding) or the shared stand-in maps to null (shared runtime),
    // never an id setHost would reject.
    LaunchedEffect(vm?.id) {
        val id = vm?.id
        val hostId = if (id != null && VmStore.get(ctx, id) != null && VmRuntime.ownRootfs(ctx, id) != null) id else null
        FlatpakHost.setHost(ctx, hostId)
        StoreState.refresh(ctx)
        flatpakApps = withContext(Dispatchers.IO) { FlatpakManager.installedApps(ctx) }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickImport by rememberSaveable { mutableStateOf(false) }
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
            suspendedHere = SessionState.suspended,
        )
    }
    fun percentOf(m: VmMachine): Int? = when {
        install.busy && install.machineId == m.id -> install.percent
        seed.busy && seed.machineId == m.id -> seed.percent
        s.busy && ownRootfs(m) == null -> s.percent
        else -> null
    }

    // A session start is remembered by the UI alone; the machine record stays untouched.
    fun launch(id: String?) {
        id?.let { VmLastStart.record(ctx, it) }
        a.onDesktop(id)
    }
    fun primaryFor(m: VmMachine?, status: VmStatus): () -> Unit = when {
        status == VmStatus.ABSENT -> ({ picking = true })
        // A running machine re-enters its live session; a suspended session resumes the same way.
        status == VmStatus.RUNNING || status == VmStatus.SUSPENDED -> a.onResume
        status == VmStatus.READY || status == VmStatus.DEGRADED -> ({ launch(m?.id) })
        // A paused, interrupted or failed install resumes from the partial it kept.
        status == VmStatus.PAUSED || status == VmStatus.INTERRUPTED || status == VmStatus.FAILED -> ({
            m?.let { if (VmPreset.start(ctx, it)) seed = VmPreset.state() }
        })
        // A machine with its own rootfs but no desktop yet gets one fitted in; the shared runtime
        // installs its desktop through the session itself, as it always has.
        status == VmStatus.IMPORTED || (status == VmStatus.NEW && ownRootfs(m) != null) -> ({
            m?.let { if (VmDesktop.start(ctx, it)) install = VmDesktop.state() }
        })
        // A built-in preset seeds its own rootfs; anything else installs through the session.
        status == VmStatus.NEW -> ({
            m?.let {
                if (VmCatalog.builtinFor(it.distro.id) != null) { if (VmPreset.start(ctx, it)) seed = VmPreset.state() }
                else launch(it.id)
            }
        })
        else -> ({})
    }
    fun primaryEnabledFor(status: VmStatus): Boolean = when (status) {
        VmStatus.ABSENT -> !s.busy
        VmStatus.NEW -> !s.busy && !s.runtimeActionsBlocked && !install.busy && !seed.busy
        // Resume/retry run on the machine alone, so a busy shared runtime does not block them.
        VmStatus.PAUSED, VmStatus.INTERRUPTED, VmStatus.FAILED -> !install.busy && !seed.busy
        VmStatus.READY, VmStatus.DEGRADED, VmStatus.RUNNING, VmStatus.SUSPENDED -> !s.busy
        VmStatus.IMPORTED -> !s.busy && !install.busy && !seed.busy && !s.runtimeActionsBlocked
        else -> false
    }
    val note = when {
        seed.busy && seed.machineId == vm?.id -> stringResource(R.string.vm_preset_seeding_running, seed.percent)
        seed.stage == VmPreset.Stage.FAILED && seed.machineId == vm?.id ->
            stringResource(R.string.vm_preset_failed, vmSeedReason(seed.reason))
        install.busy && install.machineId == vm?.id -> stringResource(R.string.vm_install_desktop_running, install.percent)
        install.stage == VmDesktop.Stage.FAILED && install.machineId == vm?.id ->
            stringResource(R.string.vm_install_desktop_failed, vmInstallReason(install.reason))
        vm?.let { statusOf(it) } == VmStatus.PAUSED -> stringResource(R.string.vm_paused_note)
        vm?.let { statusOf(it) } == VmStatus.SUSPENDED -> stringResource(R.string.vm_suspended_note)
        vm?.let { statusOf(it) } == VmStatus.INTERRUPTED -> stringResource(R.string.vm_interrupted_note)
        vm?.let { statusOf(it) } == VmStatus.FAILED -> stringResource(R.string.vm_failed_note)
        vm?.let { statusOf(it) } == VmStatus.DEGRADED -> stringResource(R.string.vm_degraded_note)
        vm?.let { statusOf(it) } == VmStatus.IMPORTED -> stringResource(R.string.vm_imported_note)
        else -> null
    }
    val running = machines.firstOrNull {
        val st = statusOf(it)
        st == VmStatus.RUNNING || st == VmStatus.SUSPENDED
    }
    val onDeleted: () -> Unit = {
        recorded = VmStore.all(ctx)
        selectedId = null
    }

    BoxWithConstraints(modifier.fillMaxSize().background(pal.background).screenTexture()) {
        // Two columns only when both axes have room; a landscape phone keeps the inline list.
        val wide = !LocalNarrowPane.current && maxWidth >= 840.dp && maxHeight >= 600.dp
        val padH = if (LocalNarrowPane.current) 20.dp else 28.dp
        val glass = LocalGlassActive.current
        val inset = LocalNavOverlayInset.current
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)
                    .then(if (glass) Modifier.glassSurface(Shape16, lens = true) else Modifier.machinedPanel(Shape16))
                    .padding(start = padH, end = padH, top = 12.dp, bottom = 10.dp),
            ) {
                Wordmark(18.dp)
                Text(
                    "/ " + stringResource(R.string.vm_title),
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                BusyChip(s)
                if (running != null) VmRunPill(
                    vmMachineLabel(ctx, running) + " · " + vmStateLabel(statusOf(running)),
                    statusOf(running),
                ) { a.onResume() }
            }
            if (machines.isEmpty()) {
                VmEmptyState(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = padH).padding(bottom = inset),
                    onPreset = { pickImport = false; picking = true },
                    onImport = { pickImport = true; picking = true },
                )
            } else if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(start = padH, end = 12.dp, bottom = maxOf(24.dp, inset)),
                    ) {
                        VmSectionLabel(machines.size)
                        for (m in machines) {
                            val st = statusOf(m)
                            VmCompactCard(m, st, m.id == vm?.id) { selectedId = m.id }
                        }
                        VmNewRow(compact = true) { picking = true }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
                    vm?.let { m ->
                        key(m.id) {
                            val st = statusOf(m)
                            Column(
                                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                                    .padding(start = 20.dp, end = padH, top = 4.dp, bottom = maxOf(24.dp, inset)),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    DistroGlyph(m.distro.id, 30.dp)
                                    Text(
                                        vmMachineLabel(ctx, m), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                                        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    VmStateChip(st)
                                }
                                val de = vmDesktopLabel(ctx, m)
                                val sub = de + if (m.imported) (if (de.isEmpty()) "" else " · ") + stringResource(R.string.vm_state_imported) else ""
                                if (sub.isNotEmpty()) Text(
                                    sub, fontSize = 12.5.sp, color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                                )
                                VmDetail(
                                    s, a, m, st, note, seed, install, flatpakApps,
                                    wide = true, primaryEnabled = primaryEnabledFor(st), onPrimary = primaryFor(m, st),
                                    sizeTick = recorded, onSettings = { settings = true }, onDeleted = onDeleted, onSelectPage = onSelect,
                                )
                            }
                        }
                    }
                }
            } else {
                val glassNav = LocalGlassNav.current
                val listScroll = rememberScrollState()
                val listContent: @Composable () -> Unit = {
                    VmSectionLabel(machines.size)
                    for (m in machines) {
                        val st = statusOf(m)
                        VmCard(
                            m, st, percentOf(m), selected = m.id == selectedId,
                            primaryEnabled = primaryEnabledFor(st), onPrimary = primaryFor(m, st),
                            onSelect = { selectedId = if (selectedId == m.id) null else m.id },
                            sizeTick = recorded,
                        ) {
                            Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(pal.hairBrush))
                            VmDetail(
                                s, a, m, st, note, seed, install, flatpakApps,
                                wide = false, primaryEnabled = primaryEnabledFor(st), onPrimary = primaryFor(m, st),
                                sizeTick = recorded, onSettings = { settings = true }, onDeleted = onDeleted, onSelectPage = onSelect,
                            )
                        }
                    }
                    VmNewRow(compact = false) { picking = true }
                    Spacer(Modifier.height(maxOf(24.dp, inset)))
                }
                if (glassNav != null) {
                    glassNav.content = listContent
                    glassNav.scroll = listScroll
                    glassNav.padH = padH
                    DisposableEffect(glassNav) {
                        onDispose {
                            glassNav.content = null
                            glassNav.scroll = null
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(listScroll).padding(horizontal = padH)
                        .onGloballyPositioned {
                            glassNav?.listOrigin = it.positionInRoot()
                            glassNav?.listSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                        },
                ) { listContent() }
            }
        }
    }
    if (picking) VmSourceDialog(
        startImporting = pickImport,
        onDismiss = { picking = false; pickImport = false },
    ) { created, start ->
        picking = false
        pickImport = false
        recorded = VmStore.all(ctx)
        selectedId = created.id
        // A preset machine starts seeding inside the wizard; take its state now so the progress
        // poll below picks it up.
        seed = VmPreset.state()
        install = VmDesktop.state()
        if (start) launch(created.id)
    }
    if (settings && vm != null) VmSettingsDialog(
        machine = vm,
        recorded = recorded,
        status = statusOf(vm),
        driverInstalled = VmDriver.driverInstalled(ownRootfs(vm)),
        hasOwnRootfs = ownRootfs(vm) != null,
        ownsMachine = vm.id != VmMachine.DEFAULT_ID,
        deleteEnabled = statusOf(vm) != VmStatus.RUNNING && statusOf(vm) != VmStatus.SUSPENDED && statusOf(vm) != VmStatus.DELETING,
        onDismiss = { settings = false },
        onRecordedChanged = { recorded = VmStore.all(ctx) },
        onRemoveMachine = {
            VmLifecycle.deleteMachine(ctx, vm)
            recorded = VmStore.all(ctx)
            selectedId = null
        },
        onRemoveDesktop = a.onRemoveDesktop,
    )
}

/** The live-session pill in the page header; one tap returns to the desktop. */
@Composable
private fun VmRunPill(label: String, status: VmStatus, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    val glass = LocalGlassActive.current
    val tint = if (status == VmStatus.RUNNING) pal.good else pal.onSurfaceVariant
    val ink = if (glass) ThemeColors.inkOn(tint, pal.glassWorst) else tint
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.paneItem("vm:runpill").clip(shape)
            .background(tint.copy(alpha = 0.10f))
            .then(if (glass) Modifier.drawBehind { drawRect(pal.glassSpec, size = Size(size.width, 1.dp.toPx())) } else Modifier)
            .glideBorder(hot, shape, pal.signal, if (glass) pal.glassLine else tint.copy(alpha = 0.4f))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
    }
}

@Composable
private fun VmSectionLabel(count: Int) {
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp),
    ) {
        Text(
            stringResource(R.string.vm_section).uppercase() + " · " + count,
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Brush.horizontalGradient(listOf(pal.hair, Color.Transparent))))
    }
}

/** The brand's one large appearance: the host frame, with the two ways a machine comes to be. */
@Composable
private fun VmEmptyState(modifier: Modifier, onPreset: () -> Unit, onImport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        DroidHostLogo(96.dp, body = colors.onBackground)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.vm_empty_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.vm_empty_body), fontSize = 13.sp, lineHeight = 19.sp,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(24.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        ) {
            VmSourceCard(
                Icons.Outlined.Inventory2, stringResource(R.string.vm_source_preset),
                stringResource(R.string.vm_source_preset_hint), Modifier.weight(1f), onPreset,
                label = stringResource(R.string.vm_new),
            )
            VmSourceCard(
                Icons.Outlined.Download, stringResource(R.string.vm_source_import_button),
                stringResource(R.string.vm_source_import_hint), Modifier.weight(1f), onImport,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun VmSourceCard(icon: ImageVector, title: String, hint: String, modifier: Modifier, onClick: () -> Unit, label: String? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Column(
        modifier.paneItem("vmsource:$title")
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .shadow(6.dp, Shape16, clip = false)
            .clip(Shape16)
            .background(Brush.verticalGradient(listOf(pal.cardTop, pal.cardBot)))
            .machinedBevel(inset = 1.dp)
            .glideBorder(hot, Shape16, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(icon, null, tint = pal.signal, modifier = Modifier.size(22.dp))
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.padding(top = 8.dp))
        Text(hint, fontSize = 11.5.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
    }
}

/** A distribution's mark, small and single-colour; the catalogue art when there is one. */
@Composable
private fun DistroGlyph(distroId: String, size: Dp, label: String? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).shadow(2.dp, shape, clip = false).clip(shape)
            .background(Brush.verticalGradient(listOf(pal.surfaceVariant, pal.surface)))
            .border(1.dp, pal.line, shape)
            .machinedBevel(inset = 1.dp),
    ) {
        val logo = VmCatalog.logoRes(distroId)
        if (logo != null) Image(
            painterResource(logo), label,
            colorFilter = ColorFilter.tint(colors.onSurfaceVariant),
            modifier = Modifier.size(size * 0.62f),
        )
        else Icon(Icons.Outlined.DesktopWindows, label, tint = colors.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun VmNewRow(compact: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val newLabel = stringResource(R.string.vm_new)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().paneItem("vm:new")
            .semantics { contentDescription = newLabel }
            .shadow(if (hot) 8.dp else 0.dp, Shape16, clip = false, ambientColor = pal.glow, spotColor = pal.glow)
            .clip(Shape16)
            .background(Brush.verticalGradient(listOf(pal.edgeHi, Color.Transparent)))
            .drawBehind {
                drawRoundRect(
                    color = if (hot) pal.signal else pal.line2, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                )
            }
            .machinedBevel(inset = 1.dp)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).shadow(2.dp, RoundedCornerShape(8.dp), clip = false)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(pal.surfaceVariant, pal.surface)))
                .machinedBevel(),
        ) {
            Icon(Icons.Filled.Add, null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Column {
            Text(
                stringResource(R.string.vm_new), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                color = if (hot) pal.signal else colors.onSurfaceVariant,
            )
            if (!compact) Text(
                stringResource(R.string.vm_new_hint), fontSize = 11.5.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun VmMetaItem(icon: ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, fontSize = 11.5.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun VmMetaDivider() {
    Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(12.dp).background(LocalPalette.current.hair))
}

/**
 * One machine as a vertical information card: what system, what desktop, what state, how big,
 * when last used, with the primary action kept at the right. The selected card expands its
 * detail in place.
 */
@Composable
private fun VmCard(
    m: VmMachine, status: VmStatus, percent: Int?, selected: Boolean,
    primaryEnabled: Boolean, onPrimary: () -> Unit, onSelect: () -> Unit,
    sizeTick: Any?,
    detail: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = Shape16
    val own = VmRuntime.ownRootfs(ctx, m.id)
    val gpu = VmDriver.driverInstalled(own) || own == null
    val usage = vmDiskUsage(m, sizeTick)
    val lit = selected || status == VmStatus.RUNNING
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).paneItem("vmcard:${m.id}")
            .shadow(
                elevation = if (hot) 14.dp else 10.dp, shape = shape, clip = false,
                ambientColor = if (lit) pal.glow else Color.Black, spotColor = if (lit) pal.glow else Color.Black,
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(pal.cardTop, pal.cardBot)))
            .drawBehind { if (hot) drawRect(pal.signal.copy(alpha = 0.06f)) }
            .machinedBevel(inset = 1.dp)
            .drawBehind {
                if (status == VmStatus.RUNNING) {
                    val barW = 3.dp.toPx()
                    val inset = 12.dp.toPx()
                    val h = size.height - inset * 2f
                    drawRoundRect(
                        pal.glow, topLeft = Offset(-barW, inset - barW),
                        size = Size(barW * 3f, h + barW * 2f), cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawRoundRect(
                        Brush.verticalGradient(listOf(pal.primary, pal.primary2)),
                        topLeft = Offset(0f, inset), size = Size(barW, h), cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
            .glideBorder(hot, shape, pal.signal, if (selected) pal.primary.copy(alpha = 0.22f) else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onSelect)
            .controllerConfirm(onClick = onSelect)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DistroGlyph(m.distro.id, 42.dp, m.distro.name)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        vmMachineLabel(ctx, m), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    VmStateChip(status)
                    if (percent != null) {
                        if (percent >= 0) CircularProgressIndicator(
                            progress = { percent / 100f }, strokeWidth = 2.dp, color = pal.signal,
                            trackColor = pal.signal.copy(alpha = 0.25f), modifier = Modifier.size(16.dp),
                        )
                        else CircularProgressIndicator(strokeWidth = 2.dp, color = pal.signal, modifier = Modifier.size(16.dp))
                    }
                }
                val de = vmDesktopLabel(ctx, m)
                val sub = de.ifEmpty { if (m.imported) stringResource(R.string.vm_imported_wait) else "" }
                if (sub.isNotEmpty()) Text(
                    sub, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            VmStartButton(m.id, vmPrimaryLabel(status), vmPrimaryIcon(status), primaryEnabled, onPrimary)
        }
        Box(Modifier.fillMaxWidth().padding(top = 11.dp).height(1.dp).background(pal.hairBrush))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 11.dp),
        ) {
            VmMetaItem(
                Icons.Outlined.Memory,
                stringResource(if (gpu) R.string.vm_settings_driver_gpu else R.string.vm_settings_driver_software),
                if (gpu) pal.good else pal.warn,
            )
            VmMetaDivider()
            VmMetaItem(Icons.Outlined.Storage, usage?.let { FileUtils.sizeToString(it.total) } ?: "…", colors.onSurfaceVariant)
            VmMetaDivider()
            VmMetaItem(Icons.Outlined.Schedule, vmLastStartText(m, status), colors.onSurfaceVariant)
        }
        if (selected) detail()
    }
}

/** The left-column card of the two-pane layout: identity and state, the detail lives beside it. */
@Composable
private fun VmCompactCard(m: VmMachine, status: VmStatus, selected: Boolean, onSelect: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = Shape12
    val lit = selected || status == VmStatus.RUNNING
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp).paneItem("vmcard:${m.id}")
            .shadow(
                elevation = if (hot) 8.dp else 4.dp, shape = shape, clip = false,
                ambientColor = if (lit) pal.glow else Color.Black, spotColor = if (lit) pal.glow else Color.Black,
            )
            .clip(shape)
            .background(Brush.verticalGradient(listOf(pal.cardTop, pal.cardBot)))
            .drawBehind { if (hot) drawRect(pal.signal.copy(alpha = 0.06f)) }
            .machinedBevel(inset = 1.dp)
            .drawBehind {
                if (status == VmStatus.RUNNING) {
                    val barW = 3.dp.toPx()
                    val inset = 12.dp.toPx()
                    val h = size.height - inset * 2f
                    drawRoundRect(
                        pal.glow, topLeft = Offset(-barW, inset - barW),
                        size = Size(barW * 3f, h + barW * 2f), cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawRoundRect(
                        Brush.verticalGradient(listOf(pal.primary, pal.primary2)),
                        topLeft = Offset(0f, inset), size = Size(barW, h), cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                }
            }
            .glideBorder(hot, shape, pal.signal, if (selected) pal.primary.copy(alpha = 0.22f) else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onSelect)
            .controllerConfirm(onClick = onSelect)
            .padding(11.dp),
    ) {
        DistroGlyph(m.distro.id, 34.dp, m.distro.name)
        Column(Modifier.weight(1f)) {
            Text(
                vmMachineLabel(ctx, m), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val de = vmDesktopLabel(ctx, m)
            if (de.isNotEmpty()) Text(
                de, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        VmStateChip(status)
    }
}

/**
 * The card's primary action. Its focus id names the machine, so several machines sharing one
 * action word ("Start") do not share one focus target.
 */
@Composable
private fun VmStartButton(id: String, label: String, icon: ImageVector?, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src) && enabled
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.955f else if (hot) 1.02f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "vmStart")
    val placed = remember { arrayOfNulls<LayoutCoordinates>(1) }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.paneItem("vmstart:$id")
            .onGloballyPositioned { placed[0] = it }
            .heightIn(min = 40.dp)
            .graphicsLayer { alpha = if (LaunchOrigin.flooding === placed) 0f else 1f; scaleX = scale; scaleY = scale }
            .clip(Shape12)
            .background(
                if (enabled) Brush.linearGradient(listOf(colors.primary, pal.primary2))
                else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surfaceVariant)),
            )
            .then(if (enabled) Modifier else Modifier.border(1.dp, pal.line2, Shape12))
            .glideBorder(hot, Shape12, pal.signal, hotFillAlpha = 0f)
            .hoverable(src).clickable(role = Role.Button, interactionSource = src, indication = null, enabled = enabled) {
                placed[0]?.takeIf { it.isAttached }?.let { LaunchOrigin.mark(it.boundsInRoot(), placed) }
                onClick()
            }
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val fg = if (enabled) colors.onPrimary else colors.onSurfaceVariant
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

private data class VmDisk(val total: Long, val cache: Long)

/** Total rootfs bytes and the share of them the host may reclaim as cache. */
@Composable
private fun vmDiskUsage(m: VmMachine, sizeTick: Any?): VmDisk? {
    val ctx = LocalContext.current
    val own = VmRuntime.ownRootfs(ctx, m.id)
    val owns = m.id != VmMachine.DEFAULT_ID
    val root = own ?: LinuxRuntime.rootDir(ctx)
    val usage by produceState<VmDisk?>(null, root, owns, sizeTick) {
        // A whole machine with no rootfs yet holds nothing; do not report the shared runtime size.
        value = if (owns && own == null) VmDisk(0L, 0L)
        else withContext(Dispatchers.IO) {
            runCatching {
                val total = FileOps.totalSize(root)
                val cache = StorageScan.VM_CACHE_DIRS.map { File(root, it) }.filter { it.isDirectory }.sumOf { FileOps.totalSize(it) }
                VmDisk(total, cache)
            }.getOrNull()
        }
    }
    return usage
}

@Composable
private fun vmLastStartText(m: VmMachine, status: VmStatus): String {
    val ctx = LocalContext.current
    if (status == VmStatus.RUNNING || status == VmStatus.SUSPENDED) return stringResource(R.string.vm_session_live)
    val at = VmLastStart.of(ctx, m.id) ?: return stringResource(R.string.vm_never_started)
    return playedSpan(at / 1000L) ?: stringResource(R.string.vm_never_started)
}

/**
 * The expanded detail of one machine: what it runs on, the actions worth keeping at hand, the
 * apps installed in it, and the session defaults it shares with the global settings.
 */
@Composable
private fun VmDetail(
    s: FrontEndState, a: FrontEndActions,
    m: VmMachine, status: VmStatus, note: String?,
    seed: VmPreset.State, install: VmDesktop.State,
    flatpakApps: List<FlatpakManager.App>,
    wide: Boolean, primaryEnabled: Boolean, onPrimary: () -> Unit,
    sizeTick: Any?,
    onSettings: () -> Unit, onDeleted: () -> Unit, onSelectPage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val own = VmRuntime.ownRootfs(ctx, m.id)
    val hasOwnRootfs = own != null
    val ownsMachine = m.id != VmMachine.DEFAULT_ID
    // The shared runtime is the app's patched build, so it always carries the driver.
    val gpu = VmDriver.driverInstalled(own) || !hasOwnRootfs
    val usage = vmDiskUsage(m, sizeTick)
    val diskText = when {
        usage == null -> "…"
        usage.cache > 0L -> stringResource(
            R.string.vm_detail_disk_cache, FileUtils.sizeToString(usage.total), FileUtils.sizeToString(usage.cache),
        )
        else -> FileUtils.sizeToString(usage.total)
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (note != null) Text(note, fontSize = 12.5.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
        if (wide) Text(
            stringResource(R.string.vm_detail_environment).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp, color = colors.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VmDetailCell(
                    stringResource(R.string.vm_detail_driver),
                    stringResource(if (gpu) R.string.vm_settings_driver_gpu else R.string.vm_settings_driver_software),
                    if (gpu) pal.good else pal.warn, Modifier.weight(1f),
                )
                VmDetailCell(stringResource(R.string.vm_detail_disk), diskText, colors.onBackground, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                VmDetailCell(
                    stringResource(R.string.vm_settings_data_dir),
                    m.dataDir.ifEmpty { stringResource(R.string.vm_settings_data_dir_none) },
                    colors.onBackground, Modifier.weight(1f), maxLines = 2,
                )
                VmDetailCell(stringResource(R.string.vm_last_start), vmLastStartText(m, status), colors.onBackground, Modifier.weight(1f))
            }
        }
        Actions {
            if (wide) PrimaryButton(
                vmPrimaryLabel(status), enabled = primaryEnabled, main = true,
                icon = vmPrimaryIcon(status), onClick = onPrimary,
            )
            SecondaryButton(stringResource(R.string.setup_tool_files), compact = true) { a.onFiles(m.id) }
            SecondaryButton(stringResource(R.string.vm_settings), compact = true) { onSettings() }
            VmExportAction(m, enabled = hasOwnRootfs && status != VmStatus.RUNNING && status != VmStatus.INSTALLING)
            // A frozen session is still live, so it can be stopped; pausing only makes sense
            // while the session runs.
            if (status == VmStatus.RUNNING || status == VmStatus.SUSPENDED)
                SecondaryButton(stringResource(R.string.vm_stop), compact = true) { a.onVmStop() }
            if (status == VmStatus.RUNNING)
                SecondaryButton(stringResource(R.string.stores_dl_pause), compact = true) { SessionService.pause(ctx) }
            // While the preset downloads, the user can pause it; an installing or paused machine
            // can cancel the install and keep the machine.
            if (seed.busy && seed.machineId == m.id && seed.percent <= 25)
                SecondaryButton(stringResource(R.string.vm_pause), compact = true) { VmPreset.pause(ctx, m.id) }
            if (hasOwnRootfs && (status == VmStatus.INSTALLING || status == VmStatus.PAUSED))
                SecondaryButton(stringResource(R.string.vm_cancel_install), compact = true) {
                    VmPreset.cancel(ctx, m.id, forDelete = false)
                    VmDesktop.cancel(ctx, m.id, forDelete = false)
                }
            // A preset or imported machine is deleted whole, even before its rootfs exists; the
            // shared runtime only loses its desktop. Two different deletions, two different wordings.
            if (ownsMachine) VmRemoveMachine(
                label = stringResource(R.string.vm_delete_machine),
                title = stringResource(R.string.vm_delete_machine_title),
                note = stringResource(R.string.vm_delete_machine_text),
                enabled = status != VmStatus.RUNNING && status != VmStatus.SUSPENDED && status != VmStatus.DELETING,
                onRemove = { VmLifecycle.deleteMachine(ctx, m); onDeleted() },
            )
            else UninstallDesktop(
                label = stringResource(R.string.vm_delete), title = stringResource(R.string.vm_delete_title),
                note = stringResource(R.string.vm_delete_text),
                enabled = !s.busy && !s.runtimeActionsBlocked, onUninstall = a.onRemoveDesktop,
            )
        }
        val showStore = status == VmStatus.READY || status == VmStatus.DEGRADED ||
            status == VmStatus.RUNNING || status == VmStatus.SUSPENDED
        if (flatpakApps.isNotEmpty() || showStore) {
            Text(
                stringResource(R.string.flatpak_apps_section).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp, color = colors.onSurfaceVariant,
            )
            VmAppChips(
                flatpakApps, showStore,
                onOpen = { id, name -> a.onFlatpakApp(id, name) },
                onStore = { onSelectPage("store") },
            )
        }
        var sessionOpen by rememberSaveable { mutableStateOf(false) }
        var sessionTab by rememberSaveable { mutableStateOf(SheetTab.DISPLAY) }
        Text(
            stringResource(R.string.vm_session_settings_link), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = pal.signal,
            modifier = Modifier.clip(Shape12)
                .clickable { sessionOpen = !sessionOpen; if (sessionOpen) a.onDesktopSettings() }
                .padding(vertical = 6.dp),
        )
        if (sessionOpen) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                for (tab in modeSheetTabs(steam = false)) VmSessionTab(tab, tab == sessionTab) {
                    sessionTab = tab
                    a.onDesktopSettings()
                }
            }
            a.modeSheet(SessionService.MODE_DESKTOP, sessionTab, SheetExtras())
        }
    }
}

@Composable
private fun VmDetailCell(label: String, value: String, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(
            value, fontSize = 12.5.sp, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun VmAppChips(apps: List<FlatpakManager.App>, showStore: Boolean, onOpen: (String, String) -> Unit, onStore: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        for (app in apps) VmAppChip(app.name, app.icon, "appchip:${app.id}", store = false) { onOpen(app.id, app.name) }
        if (showStore) VmAppChip(stringResource(R.string.flathub_name), null, "appchip:store", store = true, onClick = onStore)
    }
}

@Composable
private fun VmAppChip(name: String, icon: Any?, paneId: String, store: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.paneItem(paneId).heightIn(min = 32.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(pal.surfaceVariant, pal.surface)))
            .machinedBevel(inset = 1.dp)
            .glideBorder(hot, shape, pal.signal, if (store) pal.signal.copy(alpha = 0.35f) else pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        if (icon != null) AsyncImage(model = icon, contentDescription = null, modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)))
        else Icon(
            if (store) Icons.Outlined.Storefront else Icons.Outlined.Apps, null,
            tint = if (store) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(16.dp),
        )
        Text(name, fontSize = 11.5.sp, color = if (store) pal.signal else colors.onBackground, maxLines = 1)
    }
}

@Composable
private fun VmSessionTab(tab: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val shape = RoundedCornerShape(99.dp)
    Text(
        sheetTabLabel(tab), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (selected) pal.signal else colors.onSurfaceVariant, maxLines = 1,
        modifier = Modifier.paneItem("vmsession:$tab").clip(shape)
            .background(if (selected) pal.signal.copy(alpha = 0.12f) else colors.surfaceVariant)
            .glideBorder(hot, shape, pal.signal, if (selected) pal.signal.copy(alpha = 0.5f) else pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Export with its picker and progress dialog; shared by the detail row and the settings dialog. */
@Composable
private fun VmExportAction(machine: VmMachine, enabled: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var exporting by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(VmExport.state()) }
    LaunchedEffect(Unit) {
        while (true) {
            export = VmExport.state()
            // A cancelled export returns to idle; the progress dialog goes with it.
            if (exporting && !export.busy && export.done == null && export.error.isEmpty()) exporting = false
            delay(500)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val dir = InAppFilePicker.pickedFile(result.data) ?: return@rememberLauncherForActivityResult
        VmExport.start(ctx, machine, dir)
        export = VmExport.state()
        exporting = true
    }
    SecondaryButton(stringResource(R.string.vm_export), enabled = enabled && !export.busy, compact = true, modifier = modifier) {
        picker.launch(InAppFilePicker.buildDirIntent(ctx, ctx.getString(R.string.vm_export)))
    }
    if (exporting) GlideAlertDialog(
        onDismissRequest = { if (!export.busy) exporting = false },
        title = { Text(stringResource(R.string.vm_export)) },
        text = {
            val state = export
            when {
                state.busy -> Text(stringResource(R.string.vm_export_progress, state.percent), fontSize = 13.sp, color = colors.onSurfaceVariant)
                state.done != null -> Text(stringResource(R.string.vm_export_done, state.done.name), fontSize = 13.sp, color = colors.onBackground)
                state.error.isNotEmpty() -> Text(
                    stringResource(R.string.vm_export_failed, state.error),
                    fontSize = 13.sp, lineHeight = 18.sp, color = colors.error,
                )
            }
        },
        confirmButton = {
            if (export.busy) PadTextButton(onClick = { VmExport.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            else PadTextButton(onClick = { exporting = false }) { Text(stringResource(R.string.common_ok)) }
        },
    )
}

/**
 * What one machine is made of: the driver it runs on, the disk it uses, and the deletion that
 * fits it. A preset or imported machine is deleted whole; the shared runtime only loses its
 * desktop.
 */
@Composable
private fun VmSettingsDialog(
    machine: VmMachine,
    recorded: List<VmMachine>,
    status: VmStatus,
    driverInstalled: Boolean,
    hasOwnRootfs: Boolean,
    ownsMachine: Boolean,
    deleteEnabled: Boolean,
    onDismiss: () -> Unit,
    onRecordedChanged: () -> Unit,
    onRemoveMachine: () -> Unit,
    onRemoveDesktop: () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // A rename or data-folder change writes the store; re-read the machine from the refreshed
    // records so the dialog shows what was just saved.
    val current = recorded.firstOrNull { it.id == machine.id } ?: machine
    val root = if (hasOwnRootfs) VmRuntime.ownRootfs(ctx, current.id) else LinuxRuntime.rootDir(ctx)
    val size by produceState<Long?>(null, root, hasOwnRootfs, ownsMachine) {
        // A whole machine with no rootfs yet holds nothing; do not report the shared runtime size.
        value = if (ownsMachine && !hasOwnRootfs) 0L
            else withContext(Dispatchers.IO) { runCatching { FileOps.totalSize(root) }.getOrNull() }
    }
    val used = size ?: 0L
    val dataDirNone = stringResource(R.string.vm_settings_data_dir_none)
    var renaming by remember { mutableStateOf(false) }
    val dataDirPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val dir = InAppFilePicker.pickedFile(result.data) ?: return@rememberLauncherForActivityResult
        VmStore.setDataDir(ctx, current.id, dir.absolutePath)
        onRecordedChanged()
    }
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vm_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { renaming = true },
                ) {
                    Text(vmMachineLabel(ctx, current), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                    Icon(Icons.Filled.Edit, stringResource(R.string.vm_rename_title), tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
                if (vmDesktopLabel(ctx, current).isNotEmpty()) Text(
                    vmDesktopLabel(ctx, current), fontSize = 13.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        dataDirPicker.launch(InAppFilePicker.buildDirIntent(
                            ctx, ctx.getString(R.string.vm_settings_data_dir),
                            current.dataDir.takeIf { it.isNotEmpty() && File(it).isDirectory },
                        ))
                    }.padding(top = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.vm_settings_data_dir), fontSize = 13.sp, color = colors.onBackground)
                        Text(
                            current.dataDir.ifEmpty { dataDirNone },
                            fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (current.dataDir.isNotEmpty()) SecondaryButton(stringResource(R.string.vm_settings_data_dir_clear), compact = true) {
                        VmStore.setDataDir(ctx, current.id, "")
                        onRecordedChanged()
                    }
                }
                Text(
                    stringResource(R.string.vm_settings_data_dir_hint),
                    fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                if (hasOwnRootfs) {
                    Text(
                        stringResource(R.string.vm_settings_analysis), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                    val analysis by produceState<VmSpaceAnalysis?>(null, root) {
                        value = withContext(Dispatchers.IO) { runCatching { vmSpaceAnalysis(root) }.getOrNull() }
                    }
                    val report = analysis
                    if (report == null) Text(
                        stringResource(R.string.vm_settings_analysis_measuring),
                        fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    ) else {
                        for (entry in report.dirs) SpaceRow(entry.name, entry.bytes, report.total)
                        if (report.other > 0) SpaceRow(stringResource(R.string.vm_settings_analysis_other), report.other, report.total)
                    }
                }
                val exportBlocked = status == VmStatus.RUNNING || status == VmStatus.INSTALLING
                VmExportAction(current, enabled = hasOwnRootfs && !exportBlocked, modifier = Modifier.padding(top = 12.dp))
                if (exportBlocked) Text(
                    stringResource(R.string.vm_export_busy),
                    fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    stringResource(R.string.vm_settings_danger), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp, color = colors.error, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                if (ownsMachine) VmRemoveMachine(
                    label = stringResource(R.string.vm_delete_machine),
                    title = stringResource(R.string.vm_delete_machine_title),
                    note = stringResource(R.string.vm_delete_machine_text),
                    enabled = deleteEnabled,
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
    if (renaming) TextEntryDialog(
        stringResource(R.string.vm_rename_title),
        current.customName.ifEmpty { vmMachineLabel(ctx, current) },
        onDismiss = { renaming = false },
        onSave = { name -> VmStore.setName(ctx, current.id, name); onRecordedChanged() },
    )
}

private data class VmSpaceEntry(val name: String, val bytes: Long)

private data class VmSpaceAnalysis(val dirs: List<VmSpaceEntry>, val other: Long, val total: Long)

/** First-level real directories by size; symlinks hold no bytes of their own, so they are skipped. */
private fun vmSpaceAnalysis(root: File?): VmSpaceAnalysis? {
    if (root == null || !root.isDirectory) return null
    val total = FileOps.totalSize(root)
    val dirs = root.listFiles().orEmpty()
        .filter { it.isDirectory && !FileOps.isSymlink(it) }
        .map { VmSpaceEntry(it.name, FileOps.totalSize(it)) }
        .sortedByDescending { it.bytes }
    return VmSpaceAnalysis(dirs, total - dirs.sumOf { it.bytes }, total)
}

@Composable
private fun SpaceRow(name: String, bytes: Long, total: Long) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Text(name, fontSize = 12.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(Formatter.formatFileSize(ctx, bytes), fontSize = 12.sp, color = colors.onSurfaceVariant)
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(colors.surfaceVariant)
            .drawBehind { drawRect(pal.edgeLo, size = Size(size.width, 1.dp.toPx())) },
    ) {
        Box(
            Modifier.fillMaxWidth(if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f).height(3.dp)
                .shadow(2.dp, RoundedCornerShape(2.dp), clip = false, ambientColor = pal.glow, spotColor = pal.glow)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(pal.primary2, colors.primary))),
        )
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