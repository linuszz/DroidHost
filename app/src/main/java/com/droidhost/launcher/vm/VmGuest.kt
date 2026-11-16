package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.runtime.GuestCommand
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a program inside a machine's own rootfs.
 *
 * A machine that brought its own distribution keeps it under files/vms/<id>/rootfs, and every
 * program that belongs to that distribution has to run there: the shared runtime holds a
 * different one, with a different glibc and a different package database.
 *
 * The guest may be told to believe it is root, which is what a package manager needs. The files on
 * the device still belong to the app: proot only tells the guest otherwise.
 */
object VmGuest {
    private const val TAG = "VmGuest"

    /**
     * Where the guest's tar was given a wrapper, and where the wrapper is taken away again.
     *
     * The wrapper was there because the guest could not set the time of a file.  The fast path
     * took that ability away with it, so the real tar is wanted back.
     */
    private val TAR_SLOTS = listOf("usr/sbin/tar", "usr/local/bin/tar", "usr/local/sbin/tar")

    /** The mark the wrapper of ours carries, so that only ours is taken away. */
    private const val TAR_MARK = "DroidHost: this guest cannot set the time of a file"

    /**
     * Where the guest's apt is told how to talk to dpkg.
     *
     * dpkg asks a question when a package brings a configuration file the machine already has a
     * different version of. There is no terminal to answer it in a machine, so the answer is
     * given here: the package keeps the file it ships, and the file the machine has is kept.
     */
    private const val APT_CONFIG = "etc/apt/apt.conf.d/99droidhost"
    private const val APT_CONFIG_TEXT = "DPkg::Use-Pty \"false\";\n" +
        "DPkg::Options {\"--force-confdef\"; \"--force-confold\";};\n"

    /** What a program left behind: its exit status and everything it printed. */
    class Result(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Runs [argv] in the rootfs of the machine [machineId].
     *
     * Throws when the machine has no rootfs of its own: there is nothing to run in.
     */
    fun run(context: Context, machineId: String, argv: List<String>, guestRoot: Boolean = false,
             timeoutMs: Long = 120_000, env: Map<String, String> = emptyMap(),
             onLine: ((String) -> Unit)? = null): Result {
        val root = rootfsOf(context, machineId)
        if (root != null) prepare(root)
        val output = StringBuilder()
        val cancelled = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread({
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200) } catch (e: InterruptedException) { return@Thread }
            }
            if (!cancelled.get()) {
                timedOut.set(true)
                cancelled.set(true)
            }
        }, "vm-guest-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        val command = if (env.isEmpty()) argv
                      else listOf("/usr/bin/env") + env.map { (name, value) -> "$name=$value" } + argv
        val code = try {
            GuestCommand.run(context, command, false, null, null, null, false, cancelled, root, guestRoot) { line ->
                output.append(line).append('\n')
                onLine?.invoke(line)
            }
        } finally {
            cancelled.set(true)
            watchdog.interrupt()
        }
        return Result(code, output.toString(), timedOut.get())
    }

    /** The rootfs of a machine, or null when the machine has none of its own. */
    fun rootfsOf(context: Context, machineId: String): File? {
        val id = machineId.trim()
        if (id.isEmpty()) return null
        val dir = VmRuntime.rootfsDir(context, id)
        if (!dir.isDirectory) {
            Log.w(TAG, "the machine $id has no rootfs of its own")
            return null
        }
        return dir
    }

    /** Takes the tar wrapper of ours away, and gives the guest an apt that does not want one. */
    internal fun prepare(root: File) {
        for (slot in TAR_SLOTS) {
            val shim = File(root, slot)
            if (!shim.isFile) continue
            if (!runCatching { shim.readText() }.getOrDefault("").contains(TAR_MARK)) continue
            runCatching { shim.delete() }
                .onFailure { Log.w(TAG, "the guest kept a tar of ours", it) }
        }
        val apt = File(root, APT_CONFIG)
        val current = runCatching { apt.readText() }.getOrDefault("")
        if (current != APT_CONFIG_TEXT) {
            runCatching {
                apt.parentFile?.mkdirs()
                apt.writeText(APT_CONFIG_TEXT)
            }.onFailure { Log.w(TAG, "the guest could not be given an apt that works", it) }
        }
        VmGuestIndex.keepInside(root)
    }
}
