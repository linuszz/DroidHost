package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.R
import com.droidhost.launcher.core.Downloader
import com.droidhost.launcher.runtime.DesktopCatalog
import com.droidhost.launcher.runtime.LinuxRuntimeInstaller
import com.droidhost.launcher.runtime.RuntimeInstallService
import com.droidhost.launcher.session.SessionFiles
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a preset machine with a rootfs of its own.
 *
 * A built-in distribution downloads its rootfs from the fork's catalogue into files/vms/<id>/rootfs,
 * so each machine keeps its system to itself and several coexist without writing into each other.
 * The release carries the GPU driver and a fitted desktop, so a finished install is ready to start.
 *
 * The download runs on its own thread and keeps the app awake while it works: a rootfs holds tens
 * of thousands of files and the system freezes an app that sits in the background.
 */
object VmPreset {
    private const val TAG = "VmPreset"

    /** What the install is doing. */
    enum class Stage { IDLE, INSTALLING, READY, FAILED }

    /** Why the install did not finish. The page turns it into words. */
    enum class Reason { NONE, NO_CATALOG, DOWNLOAD_FAILED, UNPACK_FAILED, NOT_USABLE }

    /** What the page shows while a preset machine is installed. [machineId] names the machine. */
    data class State(val stage: Stage, val percent: Int, val machineId: String? = null, val reason: Reason = Reason.NONE) {
        val busy: Boolean get() = stage == Stage.INSTALLING
    }

    @Volatile
    private var progress = State(Stage.IDLE, 0)
    private val running = AtomicBoolean(false)

    /** How the install is going, and which machine it is for. */
    fun state(): State = progress

    /**
     * Starts installing the machine. Returns false when an install is already going: a second one
     * would fight the first over the same files.
     */
    fun start(context: Context, machine: VmMachine): Boolean {
        if (!running.compareAndSet(false, true)) return false
        progress = State(Stage.INSTALLING, 0, machine.id)
        Thread({ install(context.applicationContext, machine) }, "vm-preset").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun install(context: Context, machine: VmMachine) {
        val builtin = VmCatalog.builtinFor(machine.distro.id)
        val root = VmRuntime.allocateRootfs(context, machine.id)
        val hold = RuntimeInstallService.holdForPackage(context, context.getString(R.string.vm_preset_seeding))
        try {
            val release = builtin?.let { LinuxRuntimeInstaller.fetchRelease(it.catalogUrl) }
            if (release == null) {
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.NO_CATALOG)
                return
            }
            val archive = File(context.cacheDir, "vm-" + release.version.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".tar.zst")
            val downloaded = Downloader.downloadFile(release.url, archive, true, release.sha256) { fraction ->
                val percent = if (fraction < 0) 0 else (fraction * 60).toInt().coerceIn(0, 60)
                progress = State(Stage.INSTALLING, percent, machine.id)
                RuntimeInstallService.packageProgress(context.getString(R.string.vm_preset_seeding), percent)
            }
            if (!downloaded) {
                archive.delete()
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.DOWNLOAD_FAILED)
                return
            }
            val kind = VmUnpacker.Kind.of(archive.name) ?: VmUnpacker.Kind.TAR_ZST
            val written = VmUnpacker.unpack(archive, root, kind) { _, percent ->
                val overall = 60 + (percent * 35 / 100).coerceIn(0, 35)
                progress = State(Stage.INSTALLING, overall, machine.id)
                RuntimeInstallService.packageProgress(context.getString(R.string.vm_preset_seeding), overall)
            }
            archive.delete()
            if (written == null) {
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.UNPACK_FAILED)
                return
            }
            if (!VmProbeReader.probe(root).usable) {
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.NOT_USABLE)
                return
            }
            runCatching { SessionFiles.stage(context, root, false) }
                .onFailure { Log.w(TAG, "could not stage the session files", it) }
            // The release ships the GPU driver and a fitted desktop, so the machine is ready.
            VmDriver.markInstalled(root)
            if (DesktopCatalog.desktopInstalled(root)) VmDesktop.markInstalled(root)
            progress = State(Stage.READY, 100, machine.id)
        } catch (e: Exception) {
            Log.w(TAG, "the preset machine could not be installed", e)
            VmRuntime.removeInBackground(root)
            fail(machine.id, Reason.DOWNLOAD_FAILED)
        } finally {
            hold()
            running.set(false)
        }
    }

    private fun fail(machineId: String, reason: Reason) {
        Log.w(TAG, "the preset machine could not be installed: $reason")
        progress = State(Stage.FAILED, 0, machineId, reason)
    }
}
