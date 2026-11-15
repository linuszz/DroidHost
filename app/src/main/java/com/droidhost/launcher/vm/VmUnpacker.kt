package com.droidhost.launcher.vm

import android.util.Log
import com.droidhost.launcher.core.ArchivePaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Unpacks a distribution the user brought into a machine's rootfs.
 *
 * The archive is a root filesystem: it holds /bin, /etc and /usr, not a disk image. Everything
 * is written under one directory, and an entry that would escape it is refused rather than
 * written, so a crafted archive cannot write outside the machine.
 */
object VmUnpacker {
    private const val TAG = "VmUnpacker"

    /** How much of the archive has been unpacked. */
    fun interface Progress {
        fun onProgress(stage: VmStage, percent: Int)
    }

    /** The archive kinds a machine can be built from. */
    enum class Kind(val suffixes: List<String>) {
        TAR_ZST(listOf(".tar.zst", ".tzst")),
        TAR_GZ(listOf(".tar.gz", ".tgz")),
        TAR_XZ(listOf(".tar.xz", ".txz")),
        TAR_BZ2(listOf(".tar.bz2", ".tbz2", ".tbz")),
        TAR(listOf(".tar")),
        ;

        companion object {
            /** The kind an archive name points at, or null when the name says nothing. */
            fun of(name: String): Kind? {
                val lower = name.lowercase()
                for (kind in values()) if (kind.suffixes.any { lower.endsWith(it) }) return kind
                return null
            }
        }
    }

    /**
     * Unpacks [archive] into [destination].
     *
     * Returns the number of files written, or null when the archive could not be read.
     */
    fun unpack(archive: File, destination: File, kind: Kind, progress: Progress? = null): Int? {
        if (!archive.isFile) {
            Log.w(TAG, "the archive is not there: ${archive.path}")
            return null
        }
        if (!destination.isDirectory && !destination.mkdirs()) {
            Log.w(TAG, "cannot create ${destination.path}")
            return null
        }
        var written = 0
        return try {
            decompress(archive, kind).use { input ->
                TarArchiveInputStream(input).use { tar ->
                    while (true) {
                        val entry = tar.getNextTarEntry() ?: break
                        val file = ArchivePaths.inside(destination, entry.name)
                        if (file == null) {
                            // A path that leaves the rootfs is refused, not written.
                            Log.w(TAG, "skipping an entry outside the rootfs: ${entry.name}")
                            continue
                        }
                        if (entry.isDirectory) {
                            if (!file.isDirectory) file.mkdirs()
                            applyMode(file, entry.mode)
                            continue
                        }
                        if (entry.isSymbolicLink) {
                            writeLink(destination, file, entry.linkName ?: continue)
                            continue
                        }
                        if (entry.isLink) {
                            copyHardLink(destination, file, entry.linkName ?: continue)
                            continue
                        }
                        val parent = file.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            Log.w(TAG, "cannot create ${parent.path}")
                            continue
                        }
                        file.outputStream().use { out -> tar.copyTo(out) }
                        applyMode(file, entry.mode)
                        written++
                        if (written % 200 == 0) {
                            progress?.onProgress(VmStage.UNPACKING, (written / 200 * 5).coerceAtMost(95))
                        }
                    }
                }
            }
            progress?.onProgress(VmStage.UNPACKING, 100)
            written
        } catch (e: Exception) {
            Log.w(TAG, "could not unpack ${archive.name}", e)
            null
        }
    }

    /**
     * A symbolic link is only kept when it stays inside the rootfs.
     *
     * A relative target is resolved against the link's own directory and must land inside the
     * rootfs; an absolute one would point into the host's filesystem, which the machine must
     * never see, so it is dropped. Distributions ship the usual /bin -> usr/bin and
     * /etc/os-release -> ../usr/lib/os-release as relative links, so a working rootfs keeps them.
     */
    private fun writeLink(root: File, file: File, target: String) {
        val parent = file.parentFile ?: return
        if (target.startsWith("/")) {
            Log.w(TAG, "dropping a link that leaves the rootfs: ${file.name} -> $target")
            return
        }
        val resolved = File(parent, target).canonicalFile
        val base = root.canonicalFile
        if (!resolved.path.startsWith(base.path + File.separator)) {
            Log.w(TAG, "dropping a link that leaves the rootfs: ${file.name} -> $target")
            return
        }
        try {
            if (!parent.isDirectory) parent.mkdirs()
            if (file.exists()) file.delete()
            java.nio.file.Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target))
        } catch (e: Exception) {
            Log.w(TAG, "cannot keep the link ${file.name}", e)
        }
    }

    /**
     * The permissions the archive gave the file.
     *
     * A distribution's programs have to keep their execute bit: the guest cannot run its own
     * shell without it, and a session then dies before it writes a single line. Only what the
     * archive asked for is added, so a data file stays a data file.
     */
    private fun applyMode(file: File, mode: Int) {
        val bits = mode and 0777
        if (bits and 0111 != 0) file.setExecutable(true, false)
        if (bits and 0444 != 0) file.setReadable(true, false)
        if (bits and 0222 != 0) file.setWritable(true, false)
    }

    /** A hard link becomes a copy of the file it points at, which the archive wrote earlier. */
    private fun copyHardLink(destination: File, file: File, target: String) {
        val source = ArchivePaths.inside(destination, target)
        if (source == null || !source.isFile) {
            Log.w(TAG, "skipping a hard link without its file: ${file.name}")
            return
        }
        try {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) return
            source.copyTo(file, overwrite = true)
            if (source.canExecute()) file.setExecutable(true, false)
        } catch (e: IOException) {
            Log.w(TAG, "cannot copy the hard link ${file.name}", e)
        }
    }

    private fun decompress(archive: File, kind: Kind): InputStream {
        val file = BufferedInputStream(FileInputStream(archive), 1 shl 16)
        return when (kind) {
            Kind.TAR_ZST -> ZstdCompressorInputStream(file)
            Kind.TAR_GZ -> GzipCompressorInputStream(file)
            Kind.TAR_XZ -> XZCompressorInputStream(file)
            Kind.TAR_BZ2 -> BZip2CompressorInputStream(file)
            Kind.TAR -> file
        }
    }
}
