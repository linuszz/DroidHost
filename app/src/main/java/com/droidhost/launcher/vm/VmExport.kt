package com.droidhost.launcher.vm

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.droidhost.launcher.R
import com.droidhost.launcher.files.FileOps
import com.droidhost.launcher.runtime.RuntimeInstallService
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exports a machine's own rootfs as a .tar.zst archive the import side can build a machine from.
 *
 * The tree is archived as it stands: symbolic links keep their raw targets and the link2symlink
 * shadow files stay beside them, so the proot runtime layout survives the round trip. A vm.json
 * manifest rides along as the first entry and carries the machine's name and desktop.
 */
object VmExport {
    private const val TAG = "VmExport"

    // 07777: rwx plus setuid/setgid/sticky. Kotlin has no octal literals.
    private const val MODE_MASK = 0b111_111_111_111

    /** What the export is doing. [done] is the finished archive, [error] why it stopped. */
    data class State(val busy: Boolean = false, val percent: Int = 0, val done: File? = null, val error: String = "")

    @Volatile
    private var progress = State()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    /** How the export is going. */
    fun state(): State = progress

    /**
     * Starts exporting [machine] into [targetDir]. A second call while one is going is ignored.
     * A machine that runs on the shared runtime has no rootfs of its own and cannot be exported.
     */
    fun start(context: Context, machine: VmMachine, targetDir: File) {
        if (!running.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        val root = VmRuntime.ownRootfs(ctx, machine.id)
        if (root == null) {
            progress = State(error = "no own rootfs")
            running.set(false)
            return
        }
        cancelled.set(false)
        progress = State(busy = true)
        Thread({ export(ctx, machine, root, targetDir) }, "vm-export").apply {
            isDaemon = true
            start()
        }
    }

    /** Stops the export; the partial archive is thrown away and the state returns to idle. */
    fun cancel() {
        if (running.get()) cancelled.set(true)
    }

    private fun export(context: Context, machine: VmMachine, root: File, targetDir: File) {
        val hold = RuntimeInstallService.holdForPackage(context, context.getString(R.string.vm_export))
        val base = machine.customName.ifEmpty { machine.distro.name } + "-" + machine.id
        val done = File(targetDir, safeName(base) + ".tar.zst")
        val part = File(targetDir, done.name + ".part")
        try {
            if (!targetDir.isDirectory && !targetDir.mkdirs()) throw IOException("cannot create ${targetDir.path}")
            // Apparent size of the regular files: the same set the walk below counts, so the
            // fraction stays honest without following a single link.
            val total = FileOps.totalSize(root) { cancelled.get() }.coerceAtLeast(1L)
            val written = longArrayOf(0L)
            val buffer = ByteArray(1 shl 16)
            BufferedOutputStream(FileOutputStream(part), 1 shl 16).use { fileOut ->
                ZstdCompressorOutputStream(fileOut, 3).use { zstd ->
                    TarArchiveOutputStream(zstd).use { tar ->
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                        writeManifest(tar, machine)
                        walk(root, root, tar, total, written, buffer)
                    }
                }
            }
            if (cancelled.get()) throw CancellationException()
            if (!part.renameTo(done)) throw IOException("cannot finalize ${done.name}")
            progress = State(percent = 100, done = done)
        } catch (e: Exception) {
            part.delete()
            if (cancelled.get()) {
                progress = State()
            } else {
                Log.w(TAG, "could not export ${machine.id}", e)
                progress = State(error = e.message ?: e.javaClass.simpleName)
            }
        } finally {
            hold()
            running.set(false)
        }
    }

    /** The manifest the import side reads; it is not one of the guest's files. */
    private fun writeManifest(tar: TarArchiveOutputStream, machine: VmMachine) {
        val json = JSONObject()
            .put("format", 1)
            .put("name", machine.customName)
            .put("distro", JSONObject()
                .put("id", machine.distro.id)
                .put("name", machine.distro.name)
                .put("family", machine.distro.family.name)
                .put("engine", machine.distro.engine))
            .put("desktop", machine.desktop)
            .put("desktopVersion", machine.desktopVersion)
            .put("createdAt", machine.createdAt)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val entry = TarArchiveEntry("vm.json")
        entry.size = bytes.size.toLong()
        entry.mode = 0b110_100_100
        tar.putArchiveEntry(entry)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    /**
     * Archives one directory level. lstat decides the type before anything is opened or descended
     * into, so a link to a directory is archived as the link it is, never walked through.
     */
    private fun walk(dir: File, root: File, tar: TarArchiveOutputStream, total: Long, written: LongArray, buffer: ByteArray) {
        val children = dir.listFiles() ?: throw IOException("cannot list ${dir.path}")
        for (child in children) {
            if (cancelled.get()) throw CancellationException()
            val st = Os.lstat(child.path)
            val name = child.toRelativeString(root).replace(File.separatorChar, '/')
            val mode = st.st_mode and MODE_MASK
            when {
                OsConstants.S_ISDIR(st.st_mode) -> {
                    // A trailing slash is what makes the entry a directory (LF_DIR).
                    val entry = TarArchiveEntry(name + "/")
                    entry.mode = mode
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                    walk(child, root, tar, total, written, buffer)
                }
                OsConstants.S_ISLNK(st.st_mode) -> {
                    // The raw target is kept, absolute ones included: the unpacker reads them
                    // against the guest's own root, so the l2s layout round-trips unchanged.
                    val entry = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
                    entry.linkName = Os.readlink(child.path)
                    entry.mode = mode
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
                OsConstants.S_ISREG(st.st_mode) -> {
                    val entry = TarArchiveEntry(name)
                    entry.size = st.st_size
                    entry.mode = mode
                    tar.putArchiveEntry(entry)
                    FileInputStream(child).use { input ->
                        var copied = 0L
                        while (copied < st.st_size) {
                            if (cancelled.get()) throw CancellationException()
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), st.st_size - copied).toInt())
                            if (read < 0) throw IOException("short read: ${child.path}")
                            tar.write(buffer, 0, read)
                            copied += read
                        }
                    }
                    tar.closeArchiveEntry()
                    written[0] += st.st_size
                    progress = State(busy = true, percent = (written[0] * 100 / total).toInt().coerceIn(0, 99))
                }
                // Sockets and FIFOs cannot be streamed; the guest recreates them on boot.
                else -> Unit
            }
        }
    }

    /** A name that can sit in a file name whatever the machine was called. */
    private fun safeName(raw: String): String =
        raw.map { if (it.code < 32 || it in "\\/:*?\"<>|") '-' else it }.joinToString("")
}
