package com.droidhost.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmDistro
import com.droidhost.launcher.vm.VmFamily
import com.droidhost.launcher.vm.VmMachine

internal enum class VmStatus { ABSENT, NEW, INSTALLING, READY, RUNNING, DELETING }

internal fun vmStatus(s: FrontEndState, machine: VmMachine?): VmStatus = when {
    machine == null -> VmStatus.ABSENT
    // A removal is also busy, so it has to be told apart before the install branch.
    s.removalPending -> VmStatus.DELETING
    s.busy -> VmStatus.INSTALLING
    s.sessionRunning -> VmStatus.RUNNING
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
}

@Composable
internal fun vmPrimaryLabel(status: VmStatus): String = when (status) {
    VmStatus.ABSENT -> stringResource(R.string.vm_new)
    VmStatus.NEW -> stringResource(R.string.vm_install)
    VmStatus.READY -> stringResource(R.string.vm_start)
    VmStatus.RUNNING -> stringResource(R.string.vm_stop)
    VmStatus.INSTALLING -> stringResource(R.string.vm_installing)
    VmStatus.DELETING -> stringResource(R.string.vm_deleting)
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
            else DesktopCard(
                "vm:new", stringResource(R.string.vm_new), stringResource(R.string.vm_new_hint),
                art = null, fallback = Icons.Filled.Add, first = true,
            ) { onNew() }
        }
        if (machine == null) Text(
            stringResource(R.string.vm_none), fontSize = 12.5.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = start, end = 20.dp, top = 8.dp),
        )
    }
}

@Composable
internal fun VmPickerDialog(onDismiss: () -> Unit, onPick: (VmDistro) -> Unit) {
    val colors = MaterialTheme.colorScheme
    GlideAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vm_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.vm_pick_note), fontSize = 13.sp, lineHeight = 18.sp,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp),
                )
                Text(
                    stringResource(R.string.vm_one_only), fontSize = 12.sp, lineHeight = 16.sp,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 14.dp),
                )
                for (family in VmFamily.values()) {
                    val list = VmCatalog.distros.filter { it.family == family }
                    if (list.isEmpty()) continue
                    Text(
                        stringResource(family.labelRes).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                    )
                    list.forEach { VmDistroRow(it, onPick) }
                }
            }
        },
        confirmButton = { PadTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun VmDistroRow(d: VmDistro, onPick: (VmDistro) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp).clip(shape)
            .background(colors.surfaceVariant)
            .clickable(enabled = d.engine, role = Role.Button) { onPick(d) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(
            d.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground,
            modifier = Modifier.weight(1f).alpha(if (d.engine) 1f else 0.45f),
        )
        if (d.engine) Chip(stringResource(R.string.vm_install), ok = true)
        else Chip(stringResource(R.string.vm_needs_engine), ok = false)
    }
}
