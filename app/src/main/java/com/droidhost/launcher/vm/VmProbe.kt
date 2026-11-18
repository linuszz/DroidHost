package com.droidhost.launcher.vm

import java.io.File

/**
 * What a rootfs turned out to be.
 *
 * The probe reads the distribution's own files, so a machine that brings its own system is
 * described the way that system describes itself, not the way a catalog would name it.
 */
data class VmProbe(
    /** The distribution's own name, e.g. "Debian GNU/Linux 13 (trixie)". */
    val prettyName: String,
    /** The distribution id from os-release, e.g. "debian". */
    val id: String,
    val versionId: String,
    val family: String,
    /** The package manager that can install a driver package, or null when there is none. */
    val packageManager: String?,
    /** The machine architecture the rootfs was built for. */
    val architecture: String,
    /** The driver route the probe expects to work on this distribution. */
    val driverTier: VmDriverTier,
    /** Whether the rootfs carries a GPU stack of its own. */
    val hasGpuStack: Boolean,
    /** What went wrong, when the rootfs is not usable. */
    val problem: VmProblem? = null,
) {
    val usable: Boolean get() = problem == null
}

/**
 * Why a rootfs cannot be run.
 *
 * The probe names the reason instead of writing it out, so the interface can put it in the
 * user's language and the reason stays the same whoever reads it.
 */
enum class VmProblem {
    /** The rootfs could not be read at all. */
    NOT_READABLE,

    /** The rootfs holds no os-release, so it is not a Linux system. */
    NOT_A_SYSTEM,

    /** The distribution does not say which one it is. */
    NO_DISTRIBUTION,

    /** The rootfs is built for another machine architecture. */
    WRONG_ARCHITECTURE,
}

enum class VmDriverTier {
    /** A driver package we build for this distribution: GPU acceleration. */
    PACKAGED,

    /** No package we build for this distribution: configuration only, so software rendering. */
    CONFIGURATION,
}

/** The package managers a distribution can have, and what each of them needs from us. */
enum class VmPackageManager(val marker: String, val tool: String) {
    APT("apt", "/usr/bin/apt"),
    DNF("dnf", "/usr/bin/dnf"),
    APK("apk", "/sbin/apk"),
    PACMAN("pacman", "/usr/bin/pacman"),
    ZYPPER("zypper", "/usr/bin/zypper"),
    NIX("nix", "/run/current-system/sw/bin/nix"),
    ;

    companion object {
        fun of(root: File): VmPackageManager? {
            for (manager in values()) {
                if (File(root, manager.tool).isFile) return manager
                // apk and pacman keep their binaries in /sbin on some images.
                if (File(root, "sbin/" + manager.tool.substringAfterLast('/')).isFile) return manager
            }
            return null
        }
    }
}

/**
 * Reads a rootfs and says what it is.
 *
 * Everything here reads the distribution's own files; nothing is guessed from a file name, so
 * an image that lies about its name cannot make the app promise an acceleration it cannot deliver.
 */
object VmProbeReader {

    /** Import only delivers a driver when the system matches the shared runtime's
     *  distribution and release, because VmDriver reuses that runtime's apt repository. */
    private val PACKAGED = mapOf(
        "ubuntu" to setOf("26.04"),
    )

    fun probe(root: File): VmProbe {
        if (!root.isDirectory) {
            return fail(VmProblem.NOT_READABLE)
        }
        val release = readRelease(root)
        if (release == null) {
            return fail(VmProblem.NOT_A_SYSTEM)
        }
        val id = release["ID"].orEmpty().lowercase()
        val version = release["VERSION_ID"].orEmpty()
        if (id.isEmpty()) {
            return fail(VmProblem.NO_DISTRIBUTION)
        }
        val architecture = readArchitecture(root)
        if (architecture.isNotEmpty() && architecture != "aarch64") {
            return fail(VmProblem.WRONG_ARCHITECTURE, architecture)
        }
        val manager = VmPackageManager.of(root)
        val tier = if (id in PACKAGED && (PACKAGED[id]?.contains(version) ?: false)) {
            VmDriverTier.PACKAGED
        } else {
            VmDriverTier.CONFIGURATION
        }
        return VmProbe(
            prettyName = release["PRETTY_NAME"] ?: release["NAME"] ?: id,
            id = id,
            versionId = version,
            family = release["ID_LIKE"].orEmpty(),
            packageManager = manager?.name?.lowercase(),
            architecture = architecture.ifEmpty { "aarch64" },
            driverTier = tier,
            hasGpuStack = hasGpuStack(root),
        )
    }

    private fun fail(problem: VmProblem, architecture: String = "") = VmProbe(
        prettyName = "", id = "", versionId = "", family = "", packageManager = null,
        architecture = architecture, driverTier = VmDriverTier.CONFIGURATION, hasGpuStack = false,
        problem = problem,
    )

    /** The os-release fields of a rootfs, or null when it has none. */
    fun release(root: File): Map<String, String>? = readRelease(root)

    /** os-release, and the older /usr/lib/os-release a few distributions still keep. */
    private fun readRelease(root: File): Map<String, String>? {
        for (path in listOf("etc/os-release", "usr/lib/os-release")) {
            val file = File(root, path)
            if (!file.isFile) continue
            val values = LinkedHashMap<String, String>()
            for (line in file.readText().split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                val split = trimmed.indexOf('=')
                if (split <= 0) continue
                val key = trimmed.substring(0, split).trim()
                var value = trimmed.substring(split + 1).trim()
                if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                    value = value.substring(1, value.length - 1)
                }
                values[key] = value
            }
            if (values.isNotEmpty()) return values
        }
        return null
    }

    /** The architecture, read from the loader the rootfs ships. */
    private fun readArchitecture(root: File): String {
        for (path in listOf(
            "lib/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "usr/lib/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        )) {
            if (File(root, path).isFile) return "aarch64"
        }
        for (path in listOf(
            "lib/ld-linux-x86-64.so.2",
            "lib64/ld-linux-x86-64.so.2",
            "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
            "usr/lib64/ld-linux-x86-64.so.2",
            "usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2",
        )) {
            if (File(root, path).isFile) return "x86-64"
        }
        for (path in listOf("lib/ld-linux-armhf.so.3", "lib/ld-linux.so.2", "usr/lib/ld-linux.so.2")) {
            if (File(root, path).isFile) return "arm"
        }
        // NixOS keeps the loader in the store, under the glibc derivation that ships it.
        for (dir in nixStore(root, "-glibc-")) {
            if (File(dir, "lib/ld-linux-aarch64.so.1").isFile) return "aarch64"
            if (File(dir, "lib/ld-linux-x86-64.so.2").isFile) return "x86-64"
            if (File(dir, "lib/ld-linux-armhf.so.3").isFile) return "arm"
        }
        return ""
    }

    /**
     * Whether the rootfs carries a GPU stack of its own. A distribution that ships Turnip or
     * a freedreno driver may already reach the GPU; one that does not needs ours.
     */
    private fun hasGpuStack(root: File): Boolean {
        for (path in listOf(
            "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so",
            "usr/lib64/libvulkan_freedreno.so",
            // Arch Linux ARM keeps a flat /usr/lib, without a multiarch or lib64 directory.
            "usr/lib/libvulkan_freedreno.so",
            "usr/lib/aarch64-linux-gnu/dri/msm_dri.so",
            "usr/lib/dri/msm_dri.so",
            "usr/lib/aarch64-linux-gnu/libgallium_drv_video.so",
        )) {
            if (File(root, path).isFile) return true
        }
        for (dir in nixStore(root, "-mesa-")) {
            if (File(dir, "lib/dri/msm_dri.so").isFile) return true
            if (File(dir, "lib/libvulkan_freedreno.so").isFile) return true
        }
        return false
    }

    /** The store derivations whose name carries [mark], for a system that keeps everything in /nix/store. */
    private fun nixStore(root: File, mark: String): List<File> {
        val store = File(root, "nix/store")
        if (!store.isDirectory) return emptyList()
        return store.listFiles { file -> file.isDirectory && file.name.contains(mark) }.orEmpty().toList()
    }
}
