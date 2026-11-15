package com.droidhost.launcher.vm

import android.content.Context
import com.droidhost.launcher.runtime.LinuxRuntime
import java.io.File

/**
 * Where a machine keeps the rootfs of its own.
 *
 * The app's shared runtime stays where it has always been (files/linuxfs). A machine that brings
 * its own distribution keeps that rootfs beside it, under files/vms/<id>/rootfs, so the machine
 * and the shared runtime never write into each other.
 *
 * A machine without a rootfs of its own runs on the shared runtime. That is what keeps a build
 * with no machine byte-for-byte what it was before machines existed.
 */
object VmRuntime {
    const val MACHINES = "vms"
    const val ROOTFS = "rootfs"

    fun machinesDir(context: Context): File = File(context.filesDir, MACHINES)

    fun machineDir(context: Context, machineId: String): File = File(machinesDir(context), machineId)

    fun rootfsDir(context: Context, machineId: String): File = File(machineDir(context, machineId), ROOTFS)

    /** The machine's own rootfs, or null when it has none yet. */
    fun ownRootfs(context: Context, machineId: String?): File? {
        if (machineId.isNullOrEmpty()) return null
        return rootfsDir(context, machineId).takeIf { it.isDirectory }
    }

    /** Creates the machine's rootfs directory and returns it. */
    fun allocateRootfs(context: Context, machineId: String): File {
        val dir = rootfsDir(context, machineId)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /** The rootfs a machine runs in: its own when it has one, the shared runtime otherwise. */
    @JvmStatic
    fun rootFor(context: Context, machineId: String?): File =
        ownRootfs(context, machineId) ?: LinuxRuntime.rootDir(context)

    /**
     * Takes a directory away on its own thread.
     *
     * A rootfs holds tens of thousands of files, so the interface must not wait for it.
     */
    fun removeInBackground(dir: File) {
        Thread({ dir.deleteRecursively() }, "vm-remove").apply { isDaemon = true }.start()
    }

    /**
     * The machine directories that no machine points at.
     *
     * An import that was abandoned leaves its rootfs behind; nothing points at it, so it is only
     * taking up space.
     */
    fun orphans(context: Context, known: Set<String>): List<File> =
        machinesDir(context).listFiles().orEmpty().filter { it.isDirectory && it.name !in known }

    /** Takes the rootfs of an abandoned import away. */
    fun sweep(context: Context, known: Set<String>) {
        for (dir in orphans(context, known)) removeInBackground(dir)
    }
}
