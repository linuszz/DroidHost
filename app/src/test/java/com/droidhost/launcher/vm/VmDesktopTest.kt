package com.droidhost.launcher.vm

import org.junit.Assert.assertEquals
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

    @Test fun aSeedRecordsTheDesktopItCopied() {
        val root = folder.newFolder("rootfs")
        assertFalse(VmDesktop.desktopInstalled(root))
        VmDesktop.markInstalled(root)
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

    @Test fun theInstallTriesAgainWhenTheMirrorFailsIt() {
        // A mirror that replaces a file while it is being downloaded fails the whole run, and
        // what it did fetch is worth keeping for the next attempt.
        val script = VmDesktop.installScript()
        assertTrue(script.contains("for attempt in 1 2 3"))
        assertTrue(script.contains("PIPESTATUS"))
        assertTrue(script.contains("Acquire::Retries"))
    }

    @Test fun theDesktopLeavesTheSteamSideProgramsOut() {
        // A machine runs a desktop, not the games the app itself launches. gamescope drags a
        // compositor and a VR API in with it, and an older runtime still holds them.
        val wanted = VmDesktop.installable(
            listOf("plasma-desktop", "gamescope", "libopenvr-api2", "kwin-wayland"),
            setOf("gamescope", "libopenvr-api2"))
        assertEquals(listOf("plasma-desktop", "kwin-wayland"), wanted)
    }

    @Test fun aNameRepeatedOrPaddedIsAskedForOnce() {
        val wanted = VmDesktop.installable(listOf(" bash ", "", "bash", "dpkg"), emptySet())
        assertEquals(listOf("bash", "dpkg"), wanted)
    }

    @Test fun thePacmanInstallAnswersNoQuestionsAndSkipsWhatIsAlreadyThere() {
        val script = VmDesktop.installScript(pacman = true)
        assertTrue(script.contains("pacman -S --noconfirm --needed"))
        assertTrue(script.contains("for attempt in 1 2 3"))
        assertTrue(script.contains("PIPESTATUS"))
        assertTrue(script.contains("exit \$rc"))
        // The hooks that would want an init are masked before pacman runs, not by a policy file.
        assertFalse(script.contains("policy-rc.d"))
        // pacman 7 defaults the remove-all question to no; --noconfirm would keep the cache.
        assertTrue(script.contains("yes | pacman -Scc"))
    }

    @Test fun thePacmanUpdateUpgradesTheWholeSystemBeforeAnythingNew() {
        // A rolling release does not support partial upgrades: new packages must not land
        // against old libraries.
        val script = VmDesktop.pacmanUpdateScript()
        assertTrue(script.contains("pacman -Syu --noconfirm"))
        assertTrue(script.contains("pacman-key --init"))
        assertTrue(script.contains("archlinuxarm"))
    }

    @Test fun thePacmanUpdateDoesNotTrustTheDatabasesItFinds() {
        // libalpm asks the mirror with the local database's time; one kept from another mirror
        // can look newer while holding older names, and the mirror answers 304.
        val script = VmDesktop.pacmanUpdateScript()
        assertTrue(script.contains("rm -f /var/lib/pacman/sync/*.db"))
    }

    @Test fun thePacmanFilterAsksPacmanForItsRepository() {
        val script = VmDesktop.filterScript(pacman = true)
        assertTrue(script.contains("pacman -Slq"))
        assertFalse(script.contains("apt-cache"))
    }
}
