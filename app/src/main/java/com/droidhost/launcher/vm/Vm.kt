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
 * pack; the built-in distributions download one of each, so everything else stays listed but
 * disabled.
 */
data class VmDistro(val id: String, val name: String, val family: VmFamily, val engine: Boolean)

object VmCatalog {
    const val UBUNTU = "ubuntu"
    const val DEBIAN = "debian"
    const val ARCH = "arch"
    const val OMARCHY = "omarchy"

    /** The fork's component catalogue, where each built-in distribution keeps its rootfs release. */
    private const val COMPONENTS = "https://raw.githubusercontent.com/linuszz/DroidHost-Components/main/"

    val distros = listOf(
        VmDistro(UBUNTU, "Ubuntu", VmFamily.LINUX, engine = true),
        VmDistro(DEBIAN, "Debian", VmFamily.LINUX, engine = true),
        VmDistro("nixos", "NixOS", VmFamily.LINUX, engine = false),
        VmDistro("fedora", "Fedora", VmFamily.LINUX, engine = false),
        VmDistro(ARCH, "Arch Linux ARM", VmFamily.LINUX, engine = true),
        // Omarchy ships its own Arch-based rootfs preset plus the omarchy shell; it is a distribution
        // choice in its own right, not a desktop fitted onto a plain Arch machine.
        VmDistro(OMARCHY, "Omarchy", VmFamily.LINUX, engine = true),
        VmDistro("alpine", "Alpine Linux", VmFamily.LINUX, engine = false),
        VmDistro("windows-11", "Windows 11", VmFamily.WINDOWS, engine = false),
        VmDistro("windows-xp", "Windows XP", VmFamily.WINDOWS, engine = false),
        VmDistro("freebsd", "FreeBSD", VmFamily.BSD, engine = false),
        VmDistro("openbsd", "OpenBSD", VmFamily.BSD, engine = false),
        VmDistro("freedos", "FreeDOS", VmFamily.DOS, engine = false),
    )

    /** A built-in distribution version: the rootfs the app downloads for a preset machine. */
    data class Builtin(val distro: String, val version: String, val catalogUrl: String)

    val builtins = listOf(
        Builtin(UBUNTU, "26.04", COMPONENTS + "linuxfs-ubuntu.json"),
        Builtin(DEBIAN, "13", COMPONENTS + "linuxfs-debian.json"),
        Builtin(ARCH, "rolling", COMPONENTS + "linuxfs-arch.json"),
        Builtin(OMARCHY, "rolling", COMPONENTS + "linuxfs-arch.json"),
    )

    fun of(id: String?): VmDistro? = distros.firstOrNull { it.id == id }

    /** The built-in versions of a distribution, empty when it has none. */
    fun versionsFor(distroId: String): List<Builtin> = builtins.filter { it.distro == distroId }

    fun builtinFor(distroId: String): Builtin? = builtins.firstOrNull { it.distro == distroId }

    /** Card and wall art for the built-in distributions; artwork/README.md carries sources and licences. */
    fun logoRes(id: String): Int? = when (id) {
        UBUNTU -> R.drawable.distro_ubuntu
        DEBIAN -> R.drawable.distro_debian
        ARCH -> R.drawable.distro_arch
        OMARCHY -> R.drawable.distro_omarchy
        else -> null
    }
}

/** Desktop environments a machine can be fitted with (MI12P-003). Fixed at install; changing it refits. */
object VmDesktops {
    const val KDE = "kde"
    const val GNOME = "gnome"
    const val NIRI = "niri"
    const val HYPR = "hyprland"
    const val OMARCHY = "omarchy"
    val options = listOf(KDE to R.string.vm_desktop_kde, GNOME to R.string.vm_desktop_gnome, NIRI to R.string.vm_desktop_niri, HYPR to R.string.vm_desktop_hypr, OMARCHY to R.string.vm_desktop_omarchy)
    // The new-machine picker offers only desktops verified on device. GNOME stays in [options]
    // to name machines already fitted with it, but is not offered until its assembly is verified.
    val adapted = setOf(KDE)

    // niri, Hyprland and Omarchy are offered on Arch only: the Debian 13 and Ubuntu 26.04 repositories
    // carry none. Omarchy is the Hyprland DE plus omarchy's Quickshell shell (tools/desktop-omarchy);
    // its pack pins into the APK only after the first CI publish (release.env SHA256 empty until then).
    fun adaptedFor(distroId: String): Set<String> =
        when (distroId) { VmCatalog.ARCH -> adapted + NIRI + HYPR; VmCatalog.OMARCHY -> setOf(OMARCHY); else -> adapted }
}

/**
 * A machine the user owns. [id] names its rootfs under files/vms, so a machine that brings its
 * own distribution keeps it to itself.
 *
 * [imported] marks a machine the user brought rather than one we packaged, so it is restored
 * from the name it carried instead of from the catalogue.
 */
data class VmMachine(
    val distro: VmDistro,
    val createdAt: Long,
    val id: String = DEFAULT_ID,
    val imported: Boolean = false,
    // The persisted lifecycle state (VmLife name); empty means "derive from the rootfs". It survives
    // a restart so a paused or interrupted install is still known and can be resumed or deleted.
    val lifecycle: String = "",
    val failedStage: String = "",
    val failReason: String = "",
    val desktop: String = VmDesktops.KDE,
    // The fitted desktop's own version, read from the guest after install; empty until known.
    val desktopVersion: String = "",
    // A user-given name; empty falls back to the catalogue or the guest's os-release.
    val customName: String = "",
    // An external folder bound to /root/data inside the guest. Data only: FUSE cannot host the
    // rootfs or HOME itself, so /root stays on internal storage.
    val dataDir: String = "",
) {
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

    /** The first machine, a fallback for callers that have not named one. */
    fun load(context: Context): VmMachine? = all(context).firstOrNull()

    /** The machine with this id, or null when none carries it. */
    fun get(context: Context, id: String?): VmMachine? =
        if (id.isNullOrEmpty()) null else all(context).firstOrNull { it.id == id }

    /**
     * Null when the system has no engine, or when a machine already carries [id].
     *
     * Each machine keeps a rootfs of its own, so several coexist; [id] names the one this machine
     * keeps, so an import can say which rootfs it unpacked into.
     */
    fun create(context: Context, distro: VmDistro, id: String = newId(), imported: Boolean = false, desktop: String = VmDesktops.KDE): VmMachine? {
        if (!distro.engine) return null
        val machines = all(context)
        if (machines.any { it.id == id }) return null
        val machine = VmMachine(distro, System.currentTimeMillis(), id, imported, desktop = desktop)
        write(context, machines + machine)
        return machine
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(KEY).remove(KEY_MACHINES).apply()
    }

    /**
     * Removes a machine and the rootfs it kept.
     *
     * A machine that runs on the shared runtime keeps that runtime, so only a machine with a
     * rootfs of its own has files of its own to take away.
     */
    fun remove(context: Context, machine: VmMachine) {
        write(context, all(context).filter { it.id != machine.id })
        VmRuntime.ownRootfs(context, machine.id)?.parentFile?.let { VmRuntime.removeInBackground(it) }
    }

    /** Records a machine's lifecycle state so a paused or interrupted install survives a restart. */
    fun setLifecycle(context: Context, id: String, lifecycle: String, failedStage: String = "", failReason: String = "") {
        write(context, all(context).map {
            if (it.id == id) it.copy(lifecycle = lifecycle, failedStage = failedStage, failReason = failReason) else it
        })
    }

    /** Records the fitted desktop's version once the guest has been asked for it. */
    fun setDesktopVersion(context: Context, id: String, version: String) {
        write(context, all(context).map { if (it.id == id) it.copy(desktopVersion = version) else it })
    }

    fun setName(context: Context, id: String, name: String) {
        write(context, all(context).map { if (it.id == id) it.copy(customName = name.trim()) else it })
    }

    fun setDataDir(context: Context, id: String, path: String) {
        write(context, all(context).map { if (it.id == id) it.copy(dataDir = path) else it })
    }

    private fun write(context: Context, machines: List<VmMachine>) {
        val json = JSONArray()
        for (machine in machines) {
            json.put(JSONObject()
                .put("id", machine.id)
                .put("distro", machine.distro.id)
                .put("name", machine.distro.name)
                .put("imported", machine.imported)
                .put("createdAt", machine.createdAt)
                .put("lifecycle", machine.lifecycle)
                .put("failedStage", machine.failedStage)
                .put("failReason", machine.failReason)
                .put("desktop", machine.desktop)
                .put("desktopVersion", machine.desktopVersion)
                .put("customName", machine.customName)
                .put("dataDir", machine.dataDir))
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
                val id = o.optString("distro")
                val imported = o.optBoolean("imported")
                // A machine the user brought is not in the catalogue; it is restored from the
                // name it carried, so it survives a restart.
                val distro = VmCatalog.of(id)
                    ?: if (imported) VmDistro(id, o.optString("name").ifEmpty { id }, VmFamily.LINUX, engine = true)
                    else return@mapNotNull null
                // Omarchy became a first-class distro; older records stored it as Arch with an
                // Omarchy desktop. Normalize so the card and picker read Omarchy.
                val normDistro = if (o.optString("desktop") == VmDesktops.OMARCHY && distro.id == VmCatalog.ARCH) VmCatalog.of(VmCatalog.OMARCHY) ?: distro else distro
                VmMachine(normDistro, o.optLong("createdAt"),
                    o.optString("id").ifEmpty { VmMachine.DEFAULT_ID }, imported,
                    o.optString("lifecycle"), o.optString("failedStage"), o.optString("failReason"),
                    o.optString("desktop").ifEmpty { VmDesktops.KDE },
                    o.optString("desktopVersion"),
                    o.optString("customName"), o.optString("dataDir"))
            }
        }.getOrDefault(emptyList())
    }

    private val sequence = java.util.concurrent.atomic.AtomicInteger(0)

    /** The id a machine gets when the user has not named one; the counter keeps two made in the same millisecond apart. */
    fun newId(): String = "vm-" + System.currentTimeMillis().toString(36) + "-" + sequence.incrementAndGet().toString(36)
}
