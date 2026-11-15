package com.droidhost.launcher.vm

import android.util.Log
import java.io.File

/**
 * Keeps the guest's file index inside the guest.
 *
 * The phone's storage and the app's own runtime are bound into the guest, so a package that
 * builds a file index walks all of them: the index of plocate grew past half a gigabyte and was
 * still growing when it was stopped. The index is of the host, not of the system the user
 * brought, and the walk takes hours under proot.
 *
 * The guest is therefore told where its index may not go. What is left is the guest's own tree,
 * which is what the index is for.
 */
object VmGuestIndex {
    private const val TAG = "VmGuestIndex"

    /** Where the guest keeps the scope of its file index. */
    private const val CONFIG = "etc/updatedb.conf"

    /** The mark that says the scope is ours, so that it is only written once. */
    private const val MARK = "DroidHost: the guest's file index stays inside the guest"

    /** The host's trees, which the guest sees as its own. */
    private val HOST_PATHS = listOf("/data", "/storage")

    /**
     * Takes the host's trees out of the guest's file index.
     *
     * The guest's own setting is kept: the paths it already kept stay, and only the host's are
     * added.
     */
    internal fun keepInside(root: File) {
        val file = File(root, CONFIG)
        val current = runCatching { file.readText() }.getOrDefault("")
        if (current.contains(MARK)) return
        val lines = current.lines().toMutableList()
        val at = lines.indexOfFirst { it.trimStart().startsWith("PRUNEPATHS=") }
        if (at >= 0) {
            val paths = lines[at].substringAfter('=').trim().trim('"')
                .split(' ').filter { it.isNotEmpty() }
            lines[at] = "PRUNEPATHS=\"" + (paths + HOST_PATHS).distinct().joinToString(" ") + "\""
            lines.add(at, "# " + MARK)
        } else {
            lines.add("# " + MARK)
            lines.add("PRUNEPATHS=\"" + HOST_PATHS.joinToString(" ") + "\"")
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(lines.joinToString("\n") + "\n")
        }.onFailure { Log.w(TAG, "the guest could not be given a file index of its own", it) }
    }
}
