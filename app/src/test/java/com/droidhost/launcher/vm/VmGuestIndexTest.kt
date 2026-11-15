package com.droidhost.launcher.vm

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmGuestIndexTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun theHostsTreesAreTakenOutOfTheGuestsFileIndex() {
        // The phone's storage and the app's own runtime are bound into the guest, so a package
        // that builds a file index walks all of them: the index of plocate grew past half a
        // gigabyte and was still growing when it was stopped.
        val root = folder.newFolder("rootfs")
        VmGuestIndex.keepInside(root)
        val config = File(root, "etc/updatedb.conf")
        assertTrue(config.isFile)
        val paths = config.readLines().first { it.startsWith("PRUNEPATHS=") }
        assertTrue(paths.contains("/data"))
        assertTrue(paths.contains("/storage"))
    }

    @Test fun theGuestsOwnSettingIsKept() {
        val root = folder.newFolder("rootfs")
        val config = File(root, "etc/updatedb.conf")
        config.parentFile?.mkdirs()
        config.writeText("PRUNEPATHS=\"/tmp /var/spool\"\nPRUNEFS=\"proc sysfs\"\n")
        VmGuestIndex.keepInside(root)
        val text = config.readText()
        assertTrue(text.contains("/tmp"))
        assertTrue(text.contains("/var/spool"))
        assertTrue(text.contains("PRUNEFS=\"proc sysfs\""))
        assertTrue(text.contains("/data"))
    }

    @Test fun theScopeIsOnlyWrittenOnce() {
        val root = folder.newFolder("rootfs")
        VmGuestIndex.keepInside(root)
        val config = File(root, "etc/updatedb.conf")
        config.writeText(config.readText() + "\n# a line of the guest's own\n")
        VmGuestIndex.keepInside(root)
        assertTrue(config.readText().contains("a line of the guest's own"))
        assertTrue(config.readText().lines().count { it.startsWith("PRUNEPATHS=") } == 1)
    }
}
