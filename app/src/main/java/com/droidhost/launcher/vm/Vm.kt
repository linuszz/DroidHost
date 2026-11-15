package com.droidhost.launcher.vm

import android.content.Context
import com.droidhost.launcher.R
import org.json.JSONArray
import org.json.JSONObject

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

/**
 * A machine the user owns. [id] names its rootfs under files/vms, so a machine that brings its
 * own distribution keeps it to itself.
 */
data class VmMachine(val distro: VmDistro, val createdAt: Long, val id: String = DEFAULT_ID) {
    companion object {
        /** The id of the machine a build made before machines had a rootfs of their own. */
        const val DEFAULT_ID = "default"
    }
}

object VmStore {
    private const val PREFS = "vm"
    private const val KEY = "machine"
    private const val KEY_MACHINES = "machines"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every machine the user has. */
    fun all(context: Context): List<VmMachine> {
        val stored = parse(prefs(context).getString(KEY_MACHINES, null))
        if (stored.isNotEmpty()) return stored
        // A build that predates the machine list kept one machine under a single key.
        return legacy(context)?.let { listOf(it) } ?: emptyList()
    }

    private fun legacy(context: Context): VmMachine? {
        val parts = prefs(context).getString(KEY, null)?.split('|') ?: return null
        val distro = VmCatalog.of(parts.firstOrNull()) ?: return null
        return VmMachine(distro, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    /** The machine the desktop page acts on. */
    fun load(context: Context): VmMachine? = all(context).firstOrNull()

    /** Null when the system has no engine, or when a machine exists: the runtime owns one rootfs. */
    fun create(context: Context, distro: VmDistro): VmMachine? {
        if (!distro.engine || load(context) != null) return null
        val machine = VmMachine(distro, System.currentTimeMillis(), newId())
        write(context, listOf(machine))
        return machine
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY).remove(KEY_MACHINES).apply()
    }

    private fun write(context: Context, machines: List<VmMachine>) {
        val json = JSONArray()
        for (machine in machines) {
            json.put(JSONObject()
                .put("id", machine.id)
                .put("distro", machine.distro.id)
                .put("createdAt", machine.createdAt))
        }
        prefs(context).edit()
            .putString(KEY_MACHINES, json.toString())
            .putString(KEY, machines.firstOrNull()?.let { it.distro.id + "|" + it.createdAt })
            .apply()
    }

    private fun parse(raw: String?): List<VmMachine> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { i ->
                val o = json.optJSONObject(i) ?: return@mapNotNull null
                val distro = VmCatalog.of(o.optString("distro")) ?: return@mapNotNull null
                VmMachine(distro, o.optLong("createdAt"),
                    o.optString("id").ifEmpty { VmMachine.DEFAULT_ID })
            }
        }.getOrDefault(emptyList())
    }

    private fun newId(): String = "vm-" + System.currentTimeMillis().toString(36)
}
