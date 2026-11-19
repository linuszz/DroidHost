package com.droidhost.launcher.runtime

import android.content.Context
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore
import java.io.File

/**
 * Which rootfs flatpak installs into and runs from.
 *
 * Null keeps the shared runtime, byte-for-byte what flatpak did before hosts existed. A machine id
 * moves flatpak into that machine's own rootfs; the apps live there and stay there when the host
 * changes, so switching hosts swaps which set of apps is visible rather than migrating them.
 */
object FlatpakHost {
    private const val PREFS = "flatpak"
    private const val KEY_HOST = "host"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The chosen machine, or null for the shared runtime. A machine that is gone or has no rootfs of its own falls back and is forgotten. */
    fun hostMachineId(context: Context): String? {
        val id = prefs(context).getString(KEY_HOST, null)?.takeIf { it.isNotEmpty() } ?: return null
        if (VmStore.get(context, id) == null || VmRuntime.ownRootfs(context, id) == null) {
            setHost(context, null)
            return null
        }
        return id
    }

    fun setHost(context: Context, machineId: String?) {
        prefs(context).edit().putString(KEY_HOST, machineId.orEmpty()).apply()
    }

    /** The rootfs flatpak uses: the chosen machine's own, or the shared runtime. */
    fun rootfs(context: Context): File = VmRuntime.rootFor(context, hostMachineId(context))

    /** Whether setup can run: the shared runtime installed, or the chosen machine's rootfs present. */
    fun available(context: Context): Boolean {
        val id = hostMachineId(context)
        return if (id == null) LinuxRuntime.isInstalled(context) else VmRuntime.ownRootfs(context, id) != null
    }
}
