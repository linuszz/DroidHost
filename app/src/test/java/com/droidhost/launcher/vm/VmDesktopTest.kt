package com.droidhost.launcher.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmDesktopTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aMachineWithoutAMarkHasNoDesktop() {
        val root = folder.newFolder("rootfs")
        assertFalse(VmDesktop.desktopInstalled(root))
        assertFalse(VmDesktop.desktopInstalled(null))
    }

    @Test fun aMachineWithAMarkHasADesktop() {
        val root = folder.newFolder("rootfs")
        val marker = File(root, "usr/share/droiddeck/desktop-installed")
        marker.parentFile?.mkdirs()
        marker.writeText("installed 1\n")
        assertTrue(VmDesktop.desktopInstalled(root))
    }

    @Test fun theFilterAsksAptInAFormAptAccepts() {
        val script = VmDesktop.filterScript()
        // apt 2.8 rejects --no-show-versions on apt-cache show, and a query per package costs
        // minutes over the two thousand names a runtime holds.
        assertFalse(script.contains("--no-show-versions"))
        assertTrue(script.contains("apt-cache dumpavail"))
    }

    @Test fun theInstallKeepsServicesOutOfTheMachineAndReportsAptsStatus() {
        val script = VmDesktop.installScript()
        // The machine has no init to run them, so the packages must not try.
        assertTrue(script.contains("policy-rc.d"))
        assertTrue(script.contains("exit \$rc"))
    }
}
