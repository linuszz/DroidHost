package com.droidhost.launcher.vm

import android.content.Context

/**
 * The persisted lifecycle of one machine, the single source of truth the cards read.
 *
 * The install executors ([VmPreset], [VmDesktop]) write these states as they work; the UI derives
 * what to show and which operations to offer from them. Because they live in the machine record,
 * a paused or interrupted install is still known after a restart and can be resumed or deleted.
 */
enum class VmLife {
    EMPTY, DOWNLOADING, PAUSED, INSTALLING, INTERRUPTED, FAILED, READY, RUNNING, DEGRADED, IMPORTED, DELETING;

    companion object {
        fun of(name: String?): VmLife? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The reverse operations of the lifecycle: deleting a machine even while it installs, and
 * recovering installs a restart interrupted.
 *
 * Deleting a machine must not be blocked by its own install. The delete first cancels any live
 * install job for that machine and waits for it to end (releasing the runtime hold), then removes
 * the record and the rootfs. A machine with no live job is removed straight away, so an orphaned
 * or stuck machine can always be deleted.
 */
object VmLifecycle {
    private const val AWAIT_MS = 30_000L

    /** Cancels this machine's install if any, waits for it to end, then removes it and its rootfs. */
    fun deleteMachine(context: Context, machine: VmMachine) {
        val app = context.applicationContext
        VmStore.setLifecycle(app, machine.id, VmLife.DELETING.name)
        Thread({
            VmPreset.cancel(app, machine.id, forDelete = true)
            VmDesktop.cancel(app, machine.id, forDelete = true)
            VmPreset.awaitIdle(machine.id, AWAIT_MS)
            VmDesktop.awaitIdle(machine.id, AWAIT_MS)
            VmPreset.discardArchives(app, machine.id)
            VmStore.remove(app, machine)
        }, "vm-delete").apply { isDaemon = true }.start()
    }

    /**
     * Runs once at startup: an install that was in flight when the process died has no live job
     * now, so it is marked interrupted and offered resume/retry/delete instead of looking new.
     */
    fun recover(context: Context) {
        val app = context.applicationContext
        for (m in VmStore.all(app)) {
            val life = VmLife.of(m.lifecycle)
            if ((life == VmLife.DOWNLOADING || life == VmLife.INSTALLING) &&
                !VmPreset.isRunningFor(m.id) && !VmDesktop.isRunningFor(m.id)
            ) {
                VmStore.setLifecycle(app, m.id, VmLife.INTERRUPTED.name, m.failedStage, m.failReason)
            }
        }
        VmRuntime.sweep(app, VmStore.all(app).map { it.id }.toSet())
    }
}