package com.droidhost.launcher.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R
import com.droidhost.launcher.vm.VmMachine

internal enum class VmStatus { ABSENT, NEW, INSTALLING, READY, RUNNING, DELETING, IMPORTED }

internal fun vmStatus(s: FrontEndState, machine: VmMachine?): VmStatus = when {
    machine == null -> VmStatus.ABSENT
    // A removal is also busy, so it has to be told apart before the install branch.
    s.removalPending -> VmStatus.DELETING
    s.busy -> VmStatus.INSTALLING
    s.sessionRunning -> VmStatus.RUNNING
    // A machine the user brought runs on a system of its own, so the shared runtime's desktop
    // says nothing about it; its own desktop pack is not installed yet.
    machine.imported -> VmStatus.IMPORTED
    s.desktopInstalled -> VmStatus.READY
    else -> VmStatus.NEW
}

@Composable
internal fun VmStateChip(status: VmStatus) {
    when (status) {
        VmStatus.ABSENT -> Unit
        VmStatus.NEW -> Chip(stringResource(R.string.vm_state_new), ok = false)
        VmStatus.INSTALLING -> Chip(stringResource(R.string.vm_installing), ok = false)
        VmStatus.READY -> Chip(stringResource(R.string.vm_state_ready), ok = true)
        VmStatus.RUNNING -> Chip(stringResource(R.string.vm_state_running), ok = true)
        VmStatus.DELETING -> Chip(stringResource(R.string.vm_deleting), ok = false)
        VmStatus.IMPORTED -> Chip(stringResource(R.string.vm_state_imported), ok = false)
    }
}

@Composable
internal fun vmStateLabel(status: VmStatus): String = when (status) {
    VmStatus.ABSENT -> ""
    VmStatus.NEW -> stringResource(R.string.vm_state_new)
    VmStatus.INSTALLING -> stringResource(R.string.vm_installing)
    VmStatus.READY -> stringResource(R.string.vm_state_ready)
    VmStatus.RUNNING -> stringResource(R.string.vm_state_running)
    VmStatus.DELETING -> stringResource(R.string.vm_deleting)
    VmStatus.IMPORTED -> stringResource(R.string.vm_state_imported)
}

@Composable
internal fun vmPrimaryLabel(status: VmStatus): String = when (status) {
    VmStatus.ABSENT -> stringResource(R.string.vm_new)
    VmStatus.NEW -> stringResource(R.string.vm_install)
    VmStatus.READY -> stringResource(R.string.vm_start)
    VmStatus.RUNNING -> stringResource(R.string.vm_stop)
    VmStatus.INSTALLING -> stringResource(R.string.vm_installing)
    VmStatus.DELETING -> stringResource(R.string.vm_deleting)
    VmStatus.IMPORTED -> stringResource(R.string.vm_imported_wait)
}

internal fun vmPrimaryIcon(status: VmStatus): ImageVector? = when (status) {
    VmStatus.ABSENT -> Icons.Filled.Add
    VmStatus.NEW -> Icons.Outlined.DesktopWindows
    VmStatus.READY -> Icons.Filled.PlayArrow
    VmStatus.RUNNING -> Icons.Filled.Close
    else -> null
}

@Composable
internal fun VmDock(s: FrontEndState, machine: VmMachine?, status: VmStatus, onPrimary: () -> Unit, onNew: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    val start = if (narrow) 20.dp else 44.dp
    Column(Modifier.fillMaxWidth().padding(top = if (compactDesktop()) 14.dp else 22.dp)) {
        Text(
            stringResource(R.string.vm_section).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = start, bottom = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = start, end = 20.dp, top = 2.dp, bottom = 4.dp),
        ) {
            if (machine != null) DesktopCard(
                "vm:" + machine.distro.id, machine.distro.name, vmStateLabel(status),
                art = null, fallback = Icons.Outlined.DesktopWindows, first = true,
                progress = if (status == VmStatus.INSTALLING) s.percent else null,
            ) { if (!s.busy) onPrimary() }
            DesktopCard(
                "vm:new", stringResource(R.string.vm_new), stringResource(R.string.vm_new_hint),
                art = null, fallback = Icons.Filled.Add, first = machine == null,
            ) { onNew() }
        }
        if (machine == null) Text(
            stringResource(R.string.vm_none), fontSize = 12.5.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = start, end = 20.dp, top = 8.dp),
        )
    }
}

/**
 * The delete button of a machine the user brought.
 *
 * Removing such a machine takes the rootfs it unpacked with it, so the wording says what goes
 * away; the shared runtime is not touched.
 */
@Composable
internal fun VmRemoveMachine(
    label: String,
    title: String,
    note: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val canRemove by rememberUpdatedState(enabled)
    val colors = MaterialTheme.colorScheme
    val keep = stringResource(R.string.common_cancel)
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
                    StepChoice(label, danger = true, enabled = open && canRemove) { Steps.fold(); onRemove() }
                }
            })
        }
    }
}
