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

    /** What a program left behind: its exit status and everything it printed. */
    class Result(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Runs [argv] in the rootfs of the machine [machineId].
     *
     * Throws when the machine has no rootfs of its own: there is nothing to run in.
     */
    fun run(context: Context, machineId: String, argv: List<String>, guestRoot: Boolean = false,
             timeoutMs: Long = 120_000, env: Map<String, String> = emptyMap()): Result {
        val root = rootfsOf(context, machineId)
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
}
