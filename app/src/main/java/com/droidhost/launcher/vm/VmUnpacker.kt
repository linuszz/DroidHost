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

    // The access bits of a tar mode. They are octal there, and Kotlin has no octal literals.
    private const val TAR_READ = 0b100_100_100
    private const val TAR_WRITE = 0b010_010_010
    private const val TAR_EXEC = 0b001_001_001
    private const val TAR_PERMISSIONS = 0b111_111_111

    // The size from which a hard link is kept as a link instead of being written out again.
    private const val LINK_THRESHOLD = 1L shl 20

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
                            writeHardLink(destination, file, entry.linkName ?: continue)
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
     * A symbolic link is only kept when the machine can follow it without leaving its own rootfs.
     *
     * A relative target is resolved against the link's own directory and must land inside the
     * rootfs. An absolute one is read by the guest as a path in the guest's own root, so it stays
     * inside the machine: a distribution ships /bin -> usr/bin, /etc/os-release ->
     * ../usr/lib/os-release, /var/run -> /run and the links of update-alternatives that way. It
     * is kept as long as it does not climb out of the guest's root.
     *
     * The host is still out of reach: every path that is written is checked against the rootfs
     * before anything is written, so a link that points out of it is never followed on the host.
     */
    private fun writeLink(root: File, file: File, target: String) {
        val parent = file.parentFile ?: return
        val inside = if (target.startsWith("/")) !climbsOut(target) else {
            val resolved = File(parent, target).canonicalFile
            val base = root.canonicalFile
            resolved.path.startsWith(base.path + File.separator)
        }
        if (!inside) {
            Log.w(TAG, "dropping a link that leaves the rootfs: ${file.name} -> $target")
            return
        }
        try {
            if (!parent.isDirectory) parent.mkdirs()
            if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
            java.nio.file.Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(target))
        } catch (e: Exception) {
            Log.w(TAG, "cannot keep the link ${file.name}", e)
        }
    }

    /** Whether an absolute target climbs out of the guest's root. */
    private fun climbsOut(target: String): Boolean {
        var depth = 0
        for (part in target.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (--depth < 0) return true
                else -> depth++
            }
        }
        return false
    }

    /**
     * The permissions the archive gave the file.
     *
     * A distribution's programs have to keep their execute bit: the guest cannot run its own
     * shell without it, and a session then dies before it writes a single line. Only what the
     * archive asked for is added, so a data file stays a data file.
     */
    private fun applyMode(file: File, mode: Int) {
        val bits = mode and TAR_PERMISSIONS
        if ((bits and TAR_EXEC) != 0) file.setExecutable(true, false)
        if ((bits and TAR_READ) != 0) file.setReadable(true, false)
        if ((bits and TAR_WRITE) != 0) file.setWritable(true, false)
    }

    /**
     * What a hard link becomes here.
     *
     * Android refuses to create a hard link, so the other name of a file has to be written some
     * other way. A distribution ships a multi-call binary once and hard links every applet of it:
     * Ubuntu 26.04's coreutils is 10.5 MB and arrives 115 times, so copying each one costs a
     * gigabyte where the archive was a hundred megabytes. A file this large is written as a
     * relative link back to the one the archive wrote, which the guest follows and runs as it
     * would the original; anything smaller is still copied, so an applet stays a file of its own.
     */
    private fun writeHardLink(destination: File, file: File, target: String) {
        val source = ArchivePaths.inside(destination, target)
        if (source == null || !source.isFile) {
            Log.w(TAG, "skipping a hard link without its file: ${file.name}")
            return
        }
        val parent = file.parentFile
        if (parent == null) return
        if (!parent.isDirectory && !parent.mkdirs()) return
        if (source.length() >= LINK_THRESHOLD) {
            val relative = parent.canonicalFile.toPath()
                .relativize(source.canonicalFile.toPath())
                .toString()
            writeLink(destination, file, relative)
            return
        }
        try {
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
