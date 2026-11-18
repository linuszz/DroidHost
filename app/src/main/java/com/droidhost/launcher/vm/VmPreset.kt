package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.R
import com.droidhost.launcher.core.Downloader
import com.droidhost.launcher.runtime.DesktopCatalog
import com.droidhost.launcher.runtime.DesktopPack
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
        fun report(percent: Int) {
            progress = State(Stage.INSTALLING, percent, machine.id)
            RuntimeInstallService.packageProgress(context.getString(R.string.vm_preset_seeding), percent)
        }
        try {
            val release = builtin?.let { LinuxRuntimeInstaller.fetchRelease(it.catalogUrl) }
            if (release == null) {
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.NO_CATALOG)
                return
            }
            val archive = File(context.cacheDir, "vm-" + release.version.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".tar.zst")
            val downloaded = Downloader.downloadFile(release.url, archive, true, release.sha256) { fraction ->
                report(if (fraction < 0) 0 else (fraction * 25).toInt().coerceIn(0, 25))
            }
            if (!downloaded) {
                archive.delete()
                VmRuntime.removeInBackground(root)
                fail(machine.id, Reason.DOWNLOAD_FAILED)
                return
            }
            val kind = VmUnpacker.Kind.of(archive.name) ?: VmUnpacker.Kind.TAR_ZST
            val written = VmUnpacker.unpack(archive, root, kind) { _, percent ->
                report(25 + (percent * 15 / 100).coerceIn(0, 15))
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
            val desktop = seedDesktop(context, root) { report(it) }
            if (desktop != Reason.NONE) {
                VmRuntime.removeInBackground(root)
                fail(machine.id, desktop)
                return
            }
            runCatching { SessionFiles.stage(context, root, false) }
                .onFailure { Log.w(TAG, "could not stage the session files", it) }
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

    /**
     * Fits the desktop pack the base rootfs names in desktop-pack.json.
     *
     * The release splits the desktop into its own archive: the base carries only the pointer and
     * the package marker, so without this step the machine has no kwin or droiddeck-desktop and
     * the session exits (status 66) before the desktop starts.
     */
    private fun seedDesktop(context: Context, root: File, report: (Int) -> Unit): Reason {
        val pack = DesktopPack.info(root) ?: return Reason.NONE
        if (DesktopCatalog.desktopInstalled(root)) return Reason.NONE
        val archive = File(context.cacheDir, "vm-desktop-" + pack.tag.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".tar.zst")
        val downloaded = Downloader.downloadFile(pack.url, archive, true, pack.sha256) { fraction ->
            report(40 + (if (fraction < 0) 0 else (fraction * 35).toInt()).coerceIn(0, 35))
        }
        if (!downloaded) {
            archive.delete()
            return Reason.DOWNLOAD_FAILED
        }
        report(78)
        DesktopPack.prepareInstall(root)
        val extracted = LinuxRuntimeInstaller.extract(context, archive, root, null)
        archive.delete()
        if (!extracted) return Reason.UNPACK_FAILED
        DesktopPack.finishInstall(root)
        report(95)
        return if (DesktopCatalog.desktopInstalled(root)) Reason.NONE else Reason.UNPACK_FAILED
    }

    private fun fail(machineId: String, reason: Reason) {
        Log.w(TAG, "the preset machine could not be installed: $reason")
        progress = State(Stage.FAILED, 0, machineId, reason)
    }
}
