package com.droidhost.launcher.vm

import android.content.Context
import com.droidhost.launcher.R

/** macOS is absent on purpose: Apple's licence only allows virtualising it on Apple hardware. */
enum class VmFamily(val labelRes: Int) {
    LINUX(R.string.vm_family_linux),
    WINDOWS(R.string.vm_family_windows),
    BSD(R.string.vm_family_bsd),
    DOS(R.string.vm_family_dos),
}

/**
 * [engine] marks the systems a machine can boot today. A guest needs its own rootfs and desktop
 * pack, and the app ships one of each, so everything else stays listed but disabled.
 */
data class VmDistro(val id: String, val name: String, val family: VmFamily, val engine: Boolean)

object VmCatalog {
    const val UBUNTU = "ubuntu"

    val distros = listOf(
        VmDistro(UBUNTU, "Ubuntu", VmFamily.LINUX, engine = true),
        VmDistro("debian", "Debian", VmFamily.LINUX, engine = false),
        VmDistro("fedora", "Fedora", VmFamily.LINUX, engine = false),
        VmDistro("arch", "Arch Linux", VmFamily.LINUX, engine = false),
        VmDistro("alpine", "Alpine Linux", VmFamily.LINUX, engine = false),
        VmDistro("windows-11", "Windows 11", VmFamily.WINDOWS, engine = false),
        VmDistro("windows-xp", "Windows XP", VmFamily.WINDOWS, engine = false),
        VmDistro("freebsd", "FreeBSD", VmFamily.BSD, engine = false),
        VmDistro("openbsd", "OpenBSD", VmFamily.BSD, engine = false),
        VmDistro("freedos", "FreeDOS", VmFamily.DOS, engine = false),
    )

    fun of(id: String?): VmDistro? = distros.firstOrNull { it.id == id }
}

data class VmMachine(val distro: VmDistro, val createdAt: Long)

object VmStore {
    private const val PREFS = "vm"
    private const val KEY = "machine"

    fun load(context: Context): VmMachine? {
        val parts = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.split('|') ?: return null
        val distro = VmCatalog.of(parts.firstOrNull()) ?: return null
        return VmMachine(distro, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    /** Null when the system has no engine, or when a machine exists: the runtime owns one rootfs. */
    fun create(context: Context, distro: VmDistro): VmMachine? {
        if (!distro.engine || load(context) != null) return null
        val machine = VmMachine(distro, System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, distro.id + "|" + machine.createdAt).apply()
        return machine
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
