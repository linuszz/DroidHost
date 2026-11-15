package com.droidhost.launcher.vm

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

class VmUnpackerTest {
    @get:Rule val folder = TemporaryFolder()

    private fun tar(name: String, build: (TarArchiveOutputStream) -> Unit): File {
        val archive = folder.newFile(name)
        TarArchiveOutputStream(FileOutputStream(archive)).use { tar ->
            build(tar)
        }
        return archive
    }

    private fun TarArchiveOutputStream.file(path: String, content: String) {
        val entry = TarArchiveEntry(path)
        entry.size = content.toByteArray().size.toLong()
        putArchiveEntry(entry)
        write(content.toByteArray())
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.link(path: String, target: String, hard: Boolean) {
        // A link entry carries its kind in the flag the constructor takes.
        val entry = TarArchiveEntry(path, if (hard) TarConstants.LF_LINK else TarConstants.LF_SYMLINK)
        entry.linkName = target
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    @Test fun theKindOfAnArchiveIsReadFromItsName() {
        assertEquals(VmUnpacker.Kind.TAR_ZST, VmUnpacker.Kind.of("debian.tar.zst"))
        assertEquals(VmUnpacker.Kind.TAR_GZ, VmUnpacker.Kind.of("debian.tar.gz"))
        assertEquals(VmUnpacker.Kind.TAR_GZ, VmUnpacker.Kind.of("debian.tgz"))
        assertEquals(VmUnpacker.Kind.TAR_XZ, VmUnpacker.Kind.of("debian.tar.xz"))
        assertEquals(VmUnpacker.Kind.TAR_BZ2, VmUnpacker.Kind.of("debian.tar.bz2"))
        assertEquals(VmUnpacker.Kind.TAR, VmUnpacker.Kind.of("debian.tar"))
        assertNull(VmUnpacker.Kind.of("debian.iso"))
        assertNull(VmUnpacker.Kind.of("debian"))
    }

    @Test fun anArchiveIsUnpackedIntoTheRootfs() {
        val archive = tar("system.tar") { tar ->
            tar.file("etc/os-release", "ID=debian\n")
            tar.file("usr/bin/busybox", "binary")
        }
        val root = folder.newFolder()
        val written = VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertEquals(2, written)
        assertEquals("ID=debian\n", File(root, "etc/os-release").readText())
        assertTrue(File(root, "usr/bin/busybox").isFile)
    }

    @Test fun aCompressedArchiveIsUnpackedToo() {
        val archive = folder.newFile("system.tar.gz")
        GzipCompressorOutputStream(FileOutputStream(archive)).use { out ->
            TarArchiveOutputStream(out).use { tar -> tar.file("etc/os-release", "ID=debian\n") }
        }
        val root = folder.newFolder()
        assertEquals(1, VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR_GZ))
        assertEquals("ID=debian\n", File(root, "etc/os-release").readText())
    }

    @Test fun anEntryThatWouldLeaveTheRootfsIsRefused() {
        val archive = tar("system.tar") { tar ->
            tar.file("../escaped.txt", "no")
            tar.file("etc/os-release", "ID=debian\n")
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertTrue(File(root, "etc/os-release").isFile)
    }

    @Test fun aRelativeLinkInsideTheRootfsIsKept() {
        val archive = tar("system.tar") { tar ->
            tar.file("usr/bin/busybox", "binary")
            tar.link("bin/sh", "usr/bin/busybox", hard = false)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        val link = File(root, "bin/sh")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("usr/bin/busybox", Files.readSymbolicLink(link.toPath()).toString())
    }

    @Test fun aLinkThatPointsAtTheHostIsDropped() {
        val archive = tar("system.tar") { tar ->
            tar.link("bin/sh", "/usr/bin/busybox", hard = false)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertFalse(File(root, "bin/sh").exists())
    }

    @Test fun aHardLinkBecomesACopyOfTheFileItPointsAt() {
        val archive = tar("system.tar") { tar ->
            tar.file("etc/os-release", "ID=debian\n")
            tar.link("etc/os-release.backup", "etc/os-release", hard = true)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertEquals("ID=debian\n", File(root, "etc/os-release.backup").readText())
    }

    @Test fun theProgressIsReportedWhileTheArchiveIsUnpacked() {
        val archive = tar("system.tar") { tar -> tar.file("etc/os-release", "ID=debian\n") }
        val root = folder.newFolder()
        val stages = mutableListOf<VmStage>()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR) { stage, _ -> stages.add(stage) }
        assertTrue(stages.contains(VmStage.UNPACKING))
    }

    @Test fun anArchiveThatIsNotThereIsReported() {
        val root = folder.newFolder()
        assertNull(VmUnpacker.unpack(File(folder.root, "nowhere.tar"), root, VmUnpacker.Kind.TAR))
    }
}
