package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.R
import com.droidhost.launcher.files.FileOps
import com.droidhost.launcher.runtime.DesktopCatalog
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.runtime.RuntimeInstallService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Seeds a preset machine with a rootfs of its own.
 *
 * The app ships one runtime (files/linuxfs). A preset machine copies it into files/vms/<id>/rootfs
 * so it keeps its system to itself, the way an imported machine does, and several machines coexist
 * without writing into each other. The shared runtime is the app's patched build, so the copy
 * carries the GPU driver.
 *
 * The copy runs on its own thread and keeps the app awake while it works: a rootfs holds tens of
 * thousands of files and the system freezes an app that sits in the background.
 */
object VmPreset {
    private const val TAG = "VmPreset"

    /** What the seeding is doing. */
    enum class Stage { IDLE, COPYING, READY, FAILED }

    /** Why the seeding did not finish. The page turns it into words. */
    enum class Reason { NONE, NO_RUNTIME, COPY_FAILED }

    /** What the page shows while a preset machine is seeded. [machineId] names the machine. */
    data class State(val stage: Stage, val percent: Int, val machineId: String? = null, val reason: Reason = Reason.NONE) {
        val busy: Boolean get() = stage == Stage.COPYING
    }

    @Volatile
    private var progress = State(Stage.IDLE, 0)
    private val running = AtomicBoolean(false)

    /** How the seeding is going, and which machine it is for. */
    fun state(): State = progress

    /**
     * Starts seeding the machine. Returns false when a seeding is already going: a second one
     * would fight the first over the same files.
     */
    fun start(context: Context, machine: VmMachine): Boolean {
        if (!running.compareAndSet(false, true)) return false
        progress = State(Stage.COPYING, 0, machine.id)
        Thread({ seed(context.applicationContext, machine) }, "vm-preset").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun seed(context: Context, machine: VmMachine) {
        val shared = LinuxRuntime.rootDir(context)
        val root = VmRuntime.allocateRootfs(context, machine.id)
        val hold = RuntimeInstallService.holdForPackage(context, context.getString(R.string.vm_preset_seeding))
        try {
            if (!shared.isDirectory) {
                fail(machine.id, Reason.NO_RUNTIME)
                return
            }
            val ok = FileOps.copyWithProgress(shared, root, FileOps.ProgressCallback { done, total ->
                val percent = (done * 100 / maxOf(total, 1L)).toInt().coerceIn(0, 99)
                progress = State(Stage.COPYING, percent, machine.id)
                RuntimeInstallService.packageProgress(context.getString(R.string.vm_preset_seeding), percent)
            })
            if (!ok) {
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.COPY_FAILED)
                return
            }
            VmDriver.markInstalled(root)
            // The shared runtime ships a fitted desktop; a seed that copied one is ready, not new.
            if (DesktopCatalog.desktopInstalled(root)) VmDesktop.markInstalled(root)
            progress = State(Stage.READY, 100, machine.id)
        } catch (e: Exception) {
            Log.w(TAG, "the preset machine could not be seeded", e)
            VmRuntime.removeInBackground(root)
            fail(machine.id, Reason.COPY_FAILED)
        } finally {
            hold()
            running.set(false)
        }
    }

    private fun fail(machineId: String, reason: Reason) {
        Log.w(TAG, "the preset machine could not be seeded: $reason")
        progress = State(Stage.FAILED, 0, machineId, reason)
    }
}
