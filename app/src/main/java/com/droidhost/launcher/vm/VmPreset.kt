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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a preset machine with a rootfs of its own.
 *
 * A built-in distribution downloads its rootfs from the fork's catalogue into files/vms/<id>/rootfs,
 * so each machine keeps its system to itself and several coexist without writing into each other.
 * The release carries the GPU driver and a fitted desktop, so a finished install is ready to start.
 *
 * The download runs on its own thread and keeps the app awake while it works. The download carries a
 * [Downloader.Cancellation] token so the user can pause or cancel it; a paused or failed download
 * keeps its partial archive so a later resume or retry continues from where it stopped instead of
 * starting over. The lifecycle state is persisted per machine so it survives a restart.
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
    private val tokens = ConcurrentHashMap<String, Downloader.Cancellation>()
    private val intents = ConcurrentHashMap<String, String>()

    /** How the install is going, and which machine it is for. */
    fun state(): State = progress

    /** Whether the live install job belongs to this machine. */
    fun isRunningFor(machineId: String?): Boolean =
        machineId != null && running.get() && progress.machineId == machineId

    /**
     * Starts installing the machine. Returns false when an install is already going: a second one
     * would fight the first over the same files. A resume/retry calls this again; the kept partial
     * archive lets the download continue.
     */
    fun start(context: Context, machine: VmMachine): Boolean {
        if (!running.compareAndSet(false, true)) return false
        tokens[machine.id] = Downloader.Cancellation()
        intents[machine.id] = "NONE"
        VmStore.setLifecycle(context.applicationContext, machine.id, VmLife.DOWNLOADING.name)
        progress = State(Stage.INSTALLING, 0, machine.id)
        Thread({ install(context.applicationContext, machine) }, "vm-preset").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** Pauses the download, keeping the partial so a resume continues it. */
    fun pause(context: Context, machineId: String) {
        if (!isRunningFor(machineId)) return
        intents[machineId] = "PAUSE"
        tokens[machineId]?.cancel()
    }

    /** Cancels the install; [forDelete] also discards the partial and the rootfs. */
    fun cancel(context: Context, machineId: String, forDelete: Boolean) {
        if (!isRunningFor(machineId)) return
        intents[machineId] = if (forDelete) "DELETE" else "CANCEL"
        tokens[machineId]?.cancel()
    }

    /** Waits until this machine's install job has ended, so a delete does not race its writes. */
    fun awaitIdle(machineId: String, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (isRunningFor(machineId) && System.currentTimeMillis() < end) {
            runCatching { Thread.sleep(100) }
        }
    }

    /** Discards the partial archives a cancelled install kept, so a delete leaves nothing behind. */
    fun discardArchives(context: Context, machineId: String) {
        for (name in listOf("vm-$machineId.tar.zst", "vm-desktop-$machineId.tar.zst")) {
            File(context.cacheDir, name).delete()
            File(context.cacheDir, "$name.resume.properties").delete()
        }
    }

    private fun install(context: Context, machine: VmMachine) {
        val token = tokens[machine.id]
        val builtin = VmCatalog.builtinFor(machine.distro.id)
        val root = VmRuntime.allocateRootfs(context, machine.id)
        val hold = RuntimeInstallService.holdForPackage(context, context.getString(R.string.vm_preset_seeding))
        fun report(percent: Int) {
            progress = State(Stage.INSTALLING, percent, machine.id)
            RuntimeInstallService.packageProgress(context.getString(R.string.vm_preset_seeding), percent)
        }
        // A cancelled download is a pause or a cancel, not a failure; a real failure keeps the
        // partial so a retry can resume it.
        fun aborted(stage: String, reason: Reason): Boolean {
            val intent = intents[machine.id] ?: "NONE"
            val cancelled = token?.asBoolean == true
            if (cancelled && intent == "PAUSE") {
                VmStore.setLifecycle(context, machine.id, VmLife.PAUSED.name)
                progress = State(Stage.IDLE, 0, machine.id)
                return true
            }
            if (cancelled) {
                VmStore.setLifecycle(context, machine.id, VmLife.EMPTY.name)
                progress = State(Stage.IDLE, 0, machine.id)
                return true
            }
            VmStore.setLifecycle(context, machine.id, VmLife.FAILED.name, stage, reason.name)
            fail(machine.id, reason)
            return true
        }
        try {
            val release = builtin?.let { LinuxRuntimeInstaller.fetchRelease(it.catalogUrl) }
            if (release == null) {
                VmRuntime.removeInBackground(root)
                VmStore.setLifecycle(context, machine.id, VmLife.FAILED.name, "CATALOG", Reason.NO_CATALOG.name)
                fail(machine.id, Reason.NO_CATALOG)
                return
            }
            val archive = File(context.cacheDir, "vm-" + machine.id + ".tar.zst")
            val downloaded = Downloader.downloadFile(release.url, archive, true, release.sha256, token) { fraction ->
                report(if (fraction < 0) 0 else (fraction * 25).toInt().coerceIn(0, 25))
            }
            if (!downloaded) {
                if (token?.asBoolean != true) archive.delete()
                if (intents[machine.id] == "DELETE" || (token?.asBoolean == true && intents[machine.id] == "CANCEL"))
                    VmRuntime.removeInBackground(root)
                aborted("DOWNLOAD", Reason.DOWNLOAD_FAILED)
                return
            }
            VmStore.setLifecycle(context, machine.id, VmLife.INSTALLING.name)
            val kind = VmUnpacker.Kind.of(archive.name) ?: VmUnpacker.Kind.TAR_ZST
            val written = VmUnpacker.unpack(archive, root, kind) { _, percent ->
                report(25 + (percent * 15 / 100).coerceIn(0, 15))
            }
            archive.delete()
            if (written == null) {
                VmRuntime.removeInBackground(root)
                aborted("UNPACK", Reason.UNPACK_FAILED)
                return
            }
            if (!VmProbeReader.probe(root).usable) {
                VmRuntime.removeInBackground(root)
                aborted("PROBE", Reason.NOT_USABLE)
                return
            }
            val desktop = seedDesktop(context, machine.id, root, token) { report(it) }
            if (desktop != Reason.NONE) {
                if (token?.asBoolean != true) VmRuntime.removeInBackground(root)
                aborted("SEED", desktop)
                return
            }
            runCatching { SessionFiles.stage(context, root, false) }
                .onFailure { Log.w(TAG, "could not stage the session files", it) }
            VmDriver.markInstalled(root)
            if (DesktopCatalog.desktopInstalled(root)) VmDesktop.markInstalled(root)
            val life = if (DesktopCatalog.desktopInstalled(root)) VmLife.READY else VmLife.DEGRADED
            VmStore.setLifecycle(context, machine.id, life.name)
            progress = State(Stage.READY, 100, machine.id)
        } catch (e: Exception) {
            Log.w(TAG, "the preset machine could not be installed", e)
            VmRuntime.removeInBackground(root)
            aborted("DOWNLOAD", Reason.DOWNLOAD_FAILED)
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
    private fun seedDesktop(context: Context, machineId: String, root: File, token: Downloader.Cancellation?, report: (Int) -> Unit): Reason {
        val pack = DesktopPack.info(root) ?: return Reason.NONE
        if (DesktopCatalog.desktopInstalled(root)) return Reason.NONE
        val archive = File(context.cacheDir, "vm-desktop-" + machineId + ".tar.zst")
        val downloaded = Downloader.downloadFile(pack.url, archive, true, pack.sha256, token) { fraction ->
            report(40 + (if (fraction < 0) 0 else (fraction * 35).toInt()).coerceIn(0, 35))
        }
        if (!downloaded) {
            if (token?.asBoolean != true) archive.delete()
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