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

    private fun TarArchiveOutputStream.file(path: String, content: String, mode: Int = 0b110_100_100) {
        val entry = TarArchiveEntry(path)
        entry.size = content.toByteArray().size.toLong()
        entry.mode = mode
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

    @Test fun aLinkWithAnAbsoluteTargetIsKept() {
        // The guest reads an absolute link in its own root, so /usr/bin/awk -> /etc/alternatives/awk
        // stays inside the machine. Without it the guest has no awk at all, and every package
        // that needs one cannot be set up.
        val archive = tar("system.tar") { tar ->
            tar.file("usr/bin/mawk", "binary")
            tar.link("etc/alternatives/awk", "/usr/bin/mawk", hard = false)
            tar.link("usr/bin/awk", "/etc/alternatives/awk", hard = false)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        val link = File(root, "usr/bin/awk")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("/etc/alternatives/awk", Files.readSymbolicLink(link.toPath()).toString())
        assertEquals("/usr/bin/mawk", Files.readSymbolicLink(File(root, "etc/alternatives/awk").toPath()).toString())
    }

    @Test fun aLinkThatClimbsOutOfTheGuestsRootIsDropped() {
        val archive = tar("system.tar") { tar ->
            tar.link("etc/passwd", "/../../etc/passwd", hard = false)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertFalse(Files.exists(File(root, "etc/passwd").toPath()))
    }

    @Test fun aLinkThatPointsOutOfTheRootfsIsNotFollowedWhenSomethingIsWritten() {
        // The link points outside the rootfs; nothing that follows it may be written there.
        val outside = folder.newFolder("outside")
        val archive = tar("system.tar") { tar ->
            tar.link("etc/planted", outside.absolutePath, hard = false)
            tar.file("etc/planted/planted.txt", "no")
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertFalse(File(outside, "planted.txt").exists())
    }

    @Test fun aLinkThatPointsAtAnotherDirectoryOfTheRootfsIsKept() {
        // Distributions ship /etc/os-release -> ../usr/lib/os-release, so a link may leave the
        // directory it sits in as long as it stays inside the rootfs.
        val archive = tar("system.tar") { tar ->
            tar.file("usr/lib/os-release", "ID=ubuntu\n")
            tar.link("etc/os-release", "../usr/lib/os-release", hard = false)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        val link = File(root, "etc/os-release")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("ID=ubuntu\n", link.readText())
    }

    @Test fun aLinkThatLeavesTheRootfsIsDropped() {
        val archive = tar("system.tar") { tar ->
            tar.link("etc/passwd", "../../etc/passwd", hard = false)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertFalse(File(root, "etc/passwd").exists())
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

    @Test fun aProgramKeepsTheExecuteBitTheArchiveGaveIt() {
        // Without it the guest cannot run its own shell, and the session dies before it writes a
        // single line to its log.
        val archive = tar("system.tar") { tar ->
            tar.file("bin/sh", "binary", 0b111_101_101)
            tar.file("etc/passwd", "root:x:0:0", 0b110_100_100)
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertTrue(File(root, "bin/sh").canExecute())
        assertFalse(File(root, "etc/passwd").canExecute())
    }

    @Test fun aDirectoryKeepsTheExecuteBitTheArchiveGaveIt() {
        val archive = tar("system.tar") { tar ->
            // A name that ends in a slash is a directory to the tar format.
            val entry = TarArchiveEntry("usr/bin/")
            entry.mode = 0b111_000_000
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }
        val root = folder.newFolder()
        VmUnpacker.unpack(archive, root, VmUnpacker.Kind.TAR)
        assertTrue(File(root, "usr/bin").isDirectory)
        assertTrue(File(root, "usr/bin").canExecute())
    }
}
