package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Gives a machine the driver the app's own runtime has.
 *
 * The runtime carries a small apt repository of its own: the Mesa and KWin builds with the patches
 * that let them draw on the phone's GPU and nest inside the app's compositor. A machine that runs
 * the same distribution gets the same repository, so it can install the same driver.
 *
 * A machine that runs another distribution gets nothing: those builds belong to one release.
 */
object VmDriver {
    private const val TAG = "VmDriver"

    /** The repository the runtime keeps, and the two files that point apt at it. */
    private const val REPO = "var/lib/droiddeck/apt"
    private const val SOURCE = "etc/apt/sources.list.d/droiddeck.list"
    private const val PIN = "etc/apt/preferences.d/droiddeck-built"

    /** The packages that carry the driver, as the runtime's own pin names them. */
    private val PACKAGES = listOf(
        "kwin-common", "kwin-data", "kwin-wayland", "libegl-mesa0", "libgbm1",
        "libgl1-mesa-dri", "libglx-mesa0", "libkwin6", "mesa-libgallium", "mesa-vulkan-drivers",
    )

    /** Whether the machine and the runtime run the same distribution. */
    fun sameDistribution(context: Context, root: File): Boolean =
        sameRelease(File(LinuxRuntime.rootDir(context), "etc/os-release"), File(root, "etc/os-release"))

    /** Whether two systems say the same release. */
    internal fun sameRelease(shared: File, machine: File): Boolean {
        val one = versionOf(shared) ?: return false
        val other = versionOf(machine) ?: return false
        return one == other
    }

    private fun versionOf(release: File): String? =
        runCatching {
            release.readLines().firstOrNull { it.startsWith("VERSION_ID=") }
                ?.substringAfter('=')?.trim('"')
        }.getOrNull()

    /** Puts the runtime's repository into the machine. */
    fun prepare(context: Context, root: File): Boolean {
        val shared = LinuxRuntime.rootDir(context)
        val repo = File(shared, REPO)
        if (!repo.isDirectory) {
            Log.w(TAG, "the runtime has no driver repository")
            return false
        }
        return runCatching {
            val target = File(root, REPO)
            target.mkdirs()
            repo.listFiles()?.forEach { file -> file.copyTo(File(target, file.name), overwrite = true) }
            copyInto(shared, root, SOURCE)
            copyInto(shared, root, PIN)
            true
        }.getOrElse {
            Log.w(TAG, "the driver could not be put in the machine", it)
            false
        }
    }

    private fun copyInto(shared: File, root: File, path: String) {
        val from = File(shared, path)
        if (!from.isFile) return
        val to = File(root, path)
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = true)
    }

    /**
     * The script that installs the driver in the machine.
     *
     * Only the local repository is read: the packages it holds are the ones that change.
     */
    internal fun installScript(): String = """
apt-get update -o Acquire::Retries=3 -o Dir::Etc::sourcelist="sources.list.d/droiddeck.list" -o Dir::Etc::sourceparts="-" -o APT::Get::List-Cleanup="0"
apt-get install -y --no-install-recommends -o Acquire::Retries=3 ${PACKAGES.joinToString(" ")}
"""
}
