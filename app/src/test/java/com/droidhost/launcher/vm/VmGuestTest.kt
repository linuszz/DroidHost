package com.droidhost.launcher.vm

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmGuestTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun theGuestGetsATarThatLeavesTheTimeAlone() {
        // The device refuses the syscall that sets a file's time inside the guest, so the tar
        // that unpacks a package fails on every file it writes and takes the package with it.
        assertTrue(VmGuest.TAR_SCRIPT.contains("--touch"))
    }

    @Test fun theTarIsPutWherePackageManagersLook() {
        // apt hands dpkg a path of /usr/sbin:/usr/bin:/sbin:/bin, and a directory a distribution
        // package owns would have the real tar put back over this one.
        val root = folder.newFolder("rootfs")
        VmGuest.prepare(root)
        assertTrue(File(root, "usr/sbin/tar").isFile)
        assertTrue(File(root, "usr/local/bin/tar").isFile)
    }

    @Test fun theGuestIsToldAptHasNoTerminalToGiveDpkg() {
        val root = folder.newFolder("rootfs")
        VmGuest.prepare(root)
        val config = File(root, "etc/apt/apt.conf.d/99droidhost")
        assertTrue(config.isFile)
        assertTrue(config.readText().contains("DPkg::Use-Pty"))
    }
}
