package com.droidhost.launcher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R
import com.droidhost.launcher.vm.VmDesktop
import com.droidhost.launcher.vm.VmDesktops
import com.droidhost.launcher.vm.VmLife
import com.droidhost.launcher.vm.VmMachine
import com.droidhost.launcher.vm.VmPreset
import com.droidhost.launcher.vm.VmProbeReader
import com.droidhost.launcher.vm.VmRuntime

internal enum class VmStatus { ABSENT, NEW, INSTALLING, PAUSED, INTERRUPTED, FAILED, READY, DEGRADED, RUNNING, SUSPENDED, DELETING, IMPORTED }

/**
 * The status of one machine among several that coexist.
 *
 * [hasOwnRootfs] says whether the machine keeps a rootfs of its own; a machine without one runs on
 * the shared runtime, so the shared desktop and driver say everything about it. For a machine with
 * its own rootfs, [desktopInMachine] and [driverInMachine] are read from that rootfs alone: a
 * desktop without the GPU driver runs in software, which is [VmStatus.DEGRADED] rather than ready.
 * [runningHere] and [installingHere] name this machine specifically, so a session or an install on
 * one machine does not paint every card busy.
 */
internal fun vmStatus(
    s: FrontEndState,
    machine: VmMachine?,
    desktopInMachine: Boolean,
    hasOwnRootfs: Boolean,
    driverInMachine: Boolean,
    runningHere: Boolean,
    installingHere: Boolean,
    suspendedHere: Boolean = false,
): VmStatus = when {
    machine == null -> VmStatus.ABSENT
    installingHere || (s.busy && !hasOwnRootfs) -> VmStatus.INSTALLING
    // A removal of the shared desktop is also busy, so it is told apart before the ready branch.
    s.removalPending && !hasOwnRootfs -> VmStatus.DELETING
    VmLife.of(machine.lifecycle) == VmLife.DELETING -> VmStatus.DELETING
    runningHere && suspendedHere -> VmStatus.SUSPENDED
    runningHere -> VmStatus.RUNNING
    // Persisted control states survive a restart, so a paused/interrupted/failed install is known.
    VmLife.of(machine.lifecycle) == VmLife.PAUSED -> VmStatus.PAUSED
    VmLife.of(machine.lifecycle) == VmLife.INTERRUPTED -> VmStatus.INTERRUPTED
    VmLife.of(machine.lifecycle) == VmLife.FAILED -> VmStatus.FAILED
    hasOwnRootfs -> when {
        !desktopInMachine -> if (machine.imported) VmStatus.IMPORTED else VmStatus.NEW
        driverInMachine -> VmStatus.READY
        else -> VmStatus.DEGRADED
    }
    s.desktopInstalled -> VmStatus.READY
    else -> VmStatus.NEW
}

/** Why the install of a desktop did not finish, in words. */
@Composable
internal fun vmInstallReason(reason: VmDesktop.Reason): String = when (reason) {
    VmDesktop.Reason.NONE -> ""
    VmDesktop.Reason.REPOSITORY_UNREACHABLE -> stringResource(R.string.vm_install_reason_repository)
    VmDesktop.Reason.NO_PACKAGES -> stringResource(R.string.vm_install_reason_packages)
    VmDesktop.Reason.INSTALL_FAILED -> stringResource(R.string.vm_install_reason_install)
    VmDesktop.Reason.NO_SETTINGS -> stringResource(R.string.vm_install_reason_settings)
}

/** Why the install of a preset machine did not finish, in words. */
@Composable
internal fun vmSeedReason(reason: VmPreset.Reason): String = when (reason) {
    VmPreset.Reason.NONE -> ""
    VmPreset.Reason.NO_CATALOG -> stringResource(R.string.vm_seed_reason_catalog)
    VmPreset.Reason.DOWNLOAD_FAILED -> stringResource(R.string.vm_seed_reason_download)
    VmPreset.Reason.UNPACK_FAILED -> stringResource(R.string.vm_seed_reason_unpack)
    VmPreset.Reason.NOT_USABLE -> stringResource(R.string.vm_seed_reason_usable)
}

/**
 * The state pill of one machine. Ready/degraded/failed keep distinct hues in every palette, so a
 * failure never reads as ready; running is the only solid fill. Imported is dashed: brought in,
 * not yet fitted with a desktop.
 */
@Composable
internal fun VmStateChip(status: VmStatus) {
    if (status == VmStatus.ABSENT) return
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(99.dp)
    val label = vmStateLabel(status)
    when (status) {
        VmStatus.RUNNING -> {
            val ink = colors.onPrimary
            Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .shadow(6.dp, shape, clip = false, ambientColor = pal.glow, spotColor = pal.glow)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(pal.primary, pal.primary2)))
                    .machinedBevel()
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(ink))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
            }
        }
        VmStatus.IMPORTED -> Text(
            label, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(shape)
                .background(Brush.verticalGradient(listOf(pal.edgeHi, Color.Transparent)))
                .drawBehind {
                    drawRoundRect(
                        color = pal.line2, cornerRadius = CornerRadius(size.height / 2f),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                    )
                }.padding(horizontal = 9.dp, vertical = 4.dp),
        )
        else -> {
            val tint = when (status) {
                VmStatus.READY -> pal.good
                VmStatus.DEGRADED -> pal.warn
                VmStatus.FAILED, VmStatus.INTERRUPTED -> pal.error
                else -> colors.onSurfaceVariant
            }
            val border = when (status) {
                VmStatus.READY -> pal.good.copy(alpha = 0.35f)
                VmStatus.DEGRADED -> pal.warn.copy(alpha = 0.4f)
                VmStatus.FAILED, VmStatus.INTERRUPTED -> pal.error.copy(alpha = 0.4f)
                else -> pal.line2
            }
            Text(
                label, fontSize = 12.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(shape)
                    .background(Brush.verticalGradient(listOf(pal.edgeHi, Color.Transparent)))
                    .border(1.dp, border, shape)
                    .machinedBevel(inset = 1.dp)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
internal fun vmStateLabel(status: VmStatus): String = when (status) {
    VmStatus.ABSENT -> ""
    VmStatus.NEW -> stringResource(R.string.vm_state_new)
    VmStatus.INSTALLING -> stringResource(R.string.vm_installing)
    VmStatus.PAUSED -> stringResource(R.string.vm_state_paused)
    VmStatus.INTERRUPTED -> stringResource(R.string.vm_state_interrupted)
    VmStatus.FAILED -> stringResource(R.string.vm_state_failed)
    VmStatus.READY -> stringResource(R.string.vm_state_ready)
    VmStatus.DEGRADED -> stringResource(R.string.vm_state_degraded)
    VmStatus.RUNNING -> stringResource(R.string.vm_state_running)
    VmStatus.SUSPENDED -> stringResource(R.string.vm_state_paused)
    VmStatus.DELETING -> stringResource(R.string.vm_deleting)
    VmStatus.IMPORTED -> stringResource(R.string.vm_state_imported)
}

@Composable
internal fun vmPrimaryLabel(status: VmStatus): String = when (status) {
    VmStatus.ABSENT -> stringResource(R.string.vm_new)
    VmStatus.NEW -> stringResource(R.string.vm_install)
    VmStatus.READY, VmStatus.DEGRADED -> stringResource(R.string.vm_start)
    VmStatus.RUNNING -> stringResource(R.string.vm_resume)
    VmStatus.SUSPENDED -> stringResource(R.string.vm_resume)
    VmStatus.INSTALLING -> stringResource(R.string.vm_installing)
    VmStatus.PAUSED, VmStatus.INTERRUPTED -> stringResource(R.string.vm_resume)
    VmStatus.FAILED -> stringResource(R.string.vm_retry)
    VmStatus.DELETING -> stringResource(R.string.vm_deleting)
    VmStatus.IMPORTED -> stringResource(R.string.vm_install_desktop)
}

internal fun vmPrimaryIcon(status: VmStatus): ImageVector? = when (status) {
    VmStatus.ABSENT -> Icons.Filled.Add
    VmStatus.NEW -> Icons.Outlined.DesktopWindows
    VmStatus.READY, VmStatus.DEGRADED -> Icons.Filled.PlayArrow
    VmStatus.PAUSED, VmStatus.INTERRUPTED -> Icons.Filled.PlayArrow
    VmStatus.FAILED -> Icons.Filled.Refresh
    VmStatus.RUNNING -> Icons.Filled.PlayArrow
    VmStatus.SUSPENDED -> Icons.Filled.PlayArrow
    VmStatus.IMPORTED -> Icons.Outlined.Download
    else -> null
}

/**
 * A machine's name: one the user brought keeps it; a built-in one reads its own rootfs, e.g.
 * "Ubuntu 26.04 LTS" or "Debian GNU/Linux 13". A machine without a rootfs yet keeps the catalogue name.
 */
internal fun vmMachineLabel(ctx: Context, machine: VmMachine): String {
    if (machine.customName.isNotEmpty()) return machine.customName
    if (machine.imported) return machine.distro.name
    val release = VmProbeReader.release(VmRuntime.rootFor(ctx, machine.id)) ?: return machine.distro.name
    if (release["ID"].orEmpty().lowercase() != machine.distro.id) return machine.distro.name
    val name = release["NAME"] ?: return machine.distro.name
    val version = release["VERSION_ID"].orEmpty()
    if (version.isEmpty()) return name
    val lts = if (release["VERSION"].orEmpty().contains("LTS")) " LTS" else ""
    return "$name $version$lts"
}

/** The fitted desktop's name and own version, e.g. "KDE Plasma 6.3.4"; the name alone until known. */
internal fun vmDesktopLabel(ctx: Context, machine: VmMachine): String {
    val nameRes = VmDesktops.options.firstOrNull { it.first == machine.desktop }?.second ?: return ""
    val name = ctx.getString(nameRes)
    val ver = machine.desktopVersion.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
    return if (ver.isEmpty()) name else "$name $ver"
}

/**
 * When a machine was last started, kept by the UI alone: the machine record and its lifecycle
 * stay untouched. Epoch millis per machine id; absent means never started.
 */
internal object VmLastStart {
    private const val PREFS = "vm-last-start"

    fun record(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(id, System.currentTimeMillis()).apply()
    }

    fun of(ctx: Context, id: String): Long? {
        val at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(id, 0L)
        return if (at > 0L) at else null
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
