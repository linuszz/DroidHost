package com.droidhost.launcher.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droidhost.launcher.R
import com.droidhost.launcher.runtime.FlatpakHost
import com.droidhost.launcher.runtime.FlatpakManager
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.store.StoreState
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore

/**
 * Setup → Overview picker for the rootfs flatpak installs into.
 *
 * Only hosts that can run flatpak are offered: the shared runtime once installed, and a machine
 * whose own rootfs carries the flatpak binary. Each row says whether flatpak was set up there and
 * how many apps live in it, so switching hosts is an informed choice rather than a blind swap.
 */
@Composable
fun FlatpakHostGroup(host: MenuHost) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val hosts = remember(tick) { candidates(context) }
    if (hosts.isEmpty()) return
    val options = hosts.map { c ->
        val name = if (c.id.isEmpty()) stringResource(R.string.flatpak_host_shared)
            else stringResource(R.string.flatpak_host_vm, c.name)
        c.id to "$name · " + statusLabel(c.present, c.ready, c.appCount)
    }
    val selected = FlatpakHost.hostMachineId(context).orEmpty()
    SettingsGroup(stringResource(R.string.flatpak_host_title)) {
        ChoiceRow(
            host, "flatpak-host", stringResource(R.string.flatpak_host), stringResource(R.string.flatpak_host_hint),
            options, selected,
        ) { id ->
            FlatpakHost.setHost(context, id.ifEmpty { null })
            StoreState.refresh(context)
            tick++
        }
    }
}

/** The current host and its flatpak state, for the built-in apps entry to show where apps will go. */
@Composable
fun flatpakHostSummary(context: Context): String {
    val id = FlatpakHost.hostMachineId(context)
    val root = FlatpakHost.rootfs(context)
    val name = if (id == null) stringResource(R.string.flatpak_host_shared)
        else stringResource(R.string.flatpak_host_vm, VmStore.get(context, id)?.distro?.name ?: id)
    return "$name · " + statusLabel(FlatpakManager.presentIn(root), FlatpakManager.readyIn(root), FlatpakManager.appCountIn(root))
}

@Composable
private fun statusLabel(present: Boolean, ready: Boolean, appCount: Int): String = when {
    appCount > 0 -> stringResource(R.string.flatpak_host_apps, appCount)
    ready -> stringResource(R.string.flatpak_host_ready)
    present -> stringResource(R.string.flatpak_host_uninit)
    else -> stringResource(R.string.flatpak_host_unsupported)
}

private class Candidate(val id: String, val name: String, val present: Boolean, val ready: Boolean, val appCount: Int)

private fun candidates(context: Context): List<Candidate> {
    val out = ArrayList<Candidate>()
    if (LinuxRuntime.isInstalled(context)) {
        val root = LinuxRuntime.rootDir(context)
        out.add(Candidate("", "", FlatpakManager.presentIn(root), FlatpakManager.readyIn(root), FlatpakManager.appCountIn(root)))
    }
    for (machine in VmStore.all(context)) {
        val root = VmRuntime.ownRootfs(context, machine.id) ?: continue
        if (!FlatpakManager.presentIn(root)) continue
        out.add(Candidate(machine.id, machine.distro.name, true, FlatpakManager.readyIn(root), FlatpakManager.appCountIn(root)))
    }
    return out
}
