package com.droidhost.launcher.vm

import android.content.Context
import android.net.Uri
import android.util.Log
import com.droidhost.launcher.core.FileUtils
import com.droidhost.launcher.session.SessionFiles
import java.io.File
import org.json.JSONObject

/**
 * Builds a machine from a system the user brought.
 *
 * The archive is copied into the app's own storage, unpacked into the machine's rootfs and read
 * to find out what it is. Only when the user confirms what was found does the machine become
 * part of the app; a system that turns out not to be runnable leaves nothing behind.
 */
object VmImport {
    private const val TAG = "VmImport"

    /** Why an import did not end in a machine. */
    enum class Failure {
        /** The document could not be read. */
        UNREADABLE,

        /** The file is not an archive a machine can be built from. */
        NOT_AN_ARCHIVE,

        /** The archive could not be unpacked. */
        UNPACK_FAILED,

        /** The archive does not hold a system DroidHost can run; the probe says why. */
        NOT_USABLE,

        /** A machine already carries this id, so the new one could not be registered. */
        MACHINE_EXISTS,
    }

    /**
     * An archive that was read and unpacked, waiting for the user to confirm it.
     *
     * The rootfs exists but no machine points at it yet, so a system the user did not want can
     * still be thrown away.
     */
    class Staged(
        val machineId: String,
        val rootfs: File,
        val probe: VmProbe,
        val manifestName: String = "",
        val manifestDesktop: String = VmDesktops.KDE,
    ) {
        val distro: VmDistro get() = VmDistro(
            id = "imported-" + probe.id + if (probe.versionId.isEmpty()) "" else "-" + probe.versionId,
            name = probe.prettyName,
            family = VmFamily.LINUX,
            engine = true,
        )
    }

    /** What happened to an import. */
    sealed class Result {
        /** The archive was read; [staged] waits for the user to confirm it. */
        data class Prepared(val staged: Staged) : Result()

        /** The machine is registered and its rootfs holds the system. */
        data class Ready(val machine: VmMachine, val probe: VmProbe) : Result()

        /** The archive could not be read or does not hold a system we can run. */
        data class Failed(val reason: Failure, val probe: VmProbe? = null) : Result()
    }

    /**
     * Copies the archive the user picked into the app's storage.
     *
     * Returns the copy, or null when the document could not be opened.
     */
    fun copyIn(context: Context, uri: Uri, name: String): File? {
        val target = File(context.cacheDir, "vm-import-" + safeName(name))
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> FileUtils.copy(input, output) }
            } ?: return null
            target
        } catch (e: Exception) {
            Log.w(TAG, "could not read the system image", e)
            null
        }
    }

    /** A name that can sit in a file name whatever the document provider called it. */
    private fun safeName(name: String): String {
        val clean = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('.')
        return clean.ifEmpty { "system" }
    }

    /**
     * Unpacks the archive into a new machine's rootfs and reads what it is.
     *
     * The rootfs is removed again when the archive turns out not to hold a system we can run, so
     * a failed import leaves nothing behind.
     */
    fun stage(
        context: Context,
        archive: File,
        kind: VmUnpacker.Kind,
        onProgress: (VmStage, Int) -> Unit = { _, _ -> },
    ): Result {
        val machineId = VmStore.newId()
        val rootfs = VmRuntime.allocateRootfs(context, machineId)
        onProgress(VmStage.UNPACKING, 5)
        val written = VmUnpacker.unpack(archive, rootfs, kind) { stage, percent ->
            onProgress(stage, percent)
        }
        if (written == null) {
            rootfs.deleteRecursively()
            return Result.Failed(Failure.UNPACK_FAILED)
        }
        // An export's manifest rides inside the archive but is not a guest file: read it, then
        // take it out. Archives without one (or with a broken one) still go through the probe.
        var manifestName = ""
        var manifestDesktop = VmDesktops.KDE
        val manifest = File(rootfs, "vm.json")
        if (manifest.isFile) {
            runCatching {
                val json = JSONObject(manifest.readText())
                manifestName = json.optString("name")
                val desktop = json.optString("desktop")
                if (VmDesktops.options.any { it.first == desktop }) manifestDesktop = desktop
            }
            manifest.delete()
        }
        val probe = VmProbeReader.probe(rootfs)
        if (!probe.usable) {
            rootfs.deleteRecursively()
            return Result.Failed(Failure.NOT_USABLE, probe)
        }
        onProgress(VmStage.PREPARING, 97)
        // The session's own files, so the machine can be started like any other.
        runCatching { SessionFiles.stage(context, rootfs, false) }
            .onFailure { Log.w(TAG, "could not stage the session files", it) }
        return Result.Prepared(Staged(machineId, rootfs, probe, manifestName, manifestDesktop))
    }

    /**
     * Registers a machine the user confirmed.
     *
     * The machine joins the ones already here, each keeping a rootfs of its own; the system the
     * user brought is added only now, when it is known to be good.
     */
    fun register(context: Context, staged: Staged): Result {
        val machine = VmStore.create(context, staged.distro, staged.machineId, imported = true, desktop = staged.manifestDesktop)
        if (machine == null) {
            staged.rootfs.deleteRecursively()
            return Result.Failed(Failure.MACHINE_EXISTS)
        }
        if (staged.manifestName.isNotEmpty()) {
            VmStore.setName(context, machine.id, staged.manifestName)
            return Result.Ready(machine.copy(customName = staged.manifestName.trim()), staged.probe)
        }
        return Result.Ready(machine, staged.probe)
    }

    /** Throws away a system the user did not confirm. */
    fun discard(staged: Staged) {
        VmRuntime.removeInBackground(staged.rootfs)
    }
}
