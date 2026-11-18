package com.droidhost.launcher.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmGuestTest {
    @get:Rule val folder = TemporaryFolder()

    private fun rootWith(vararg tools: String): File {
        val root = folder.newFolder("rootfs")
        for (tool in tools) {
            val file = File(root, tool)
            file.parentFile?.mkdirs()
            file.writeText("")
        }
        return root
    }

    @Test fun theGuestKeepsTheTarItCameWith() {
        // The guest could not set the time of a file for a while, and the tar that unpacks a
        // package was given a wrapper to leave the time alone.  That is taken away again, so the
        // real tar is used and the time of a file is the one the package says it is.
        val root = folder.newFolder("rootfs")
        File(root, "usr/sbin").mkdirs()
        val tar = File(root, "usr/sbin/tar")
        tar.writeText("#!/bin/sh\nexec /bin/tar \"$@\"\n")
        VmGuest.prepare(root)
        assertTrue(tar.isFile)
    }

    @Test fun theWrapperOfOursIsTakenAway() {
        val root = folder.newFolder("rootfs")
        File(root, "usr/sbin").mkdirs()
        val tar = File(root, "usr/sbin/tar")
        tar.writeText("#!/bin/sh\n# DroidHost: this guest cannot set the time of a file\n")
        VmGuest.prepare(root)
        assertFalse(tar.exists())
    }

    @Test fun theGuestIsToldAptHasNoTerminalToGiveDpkg() {
        val root = rootWith("usr/bin/apt")
        VmGuest.prepare(root)
        val config = File(root, "etc/apt/apt.conf.d/99droidhost")
        assertTrue(config.isFile)
        assertTrue(config.readText().contains("DPkg::Use-Pty"))
    }

    @Test fun theGuestIsToldHowDpkgShouldAnswerAConffile() {
        // dpkg asks a question when a package brings a configuration file that the machine
        // already has another version of.  There is no terminal to answer it in, so the answer
        // is given to dpkg instead of waited for.
        val root = rootWith("usr/bin/apt")
        VmGuest.prepare(root)
        val text = File(root, "etc/apt/apt.conf.d/99droidhost").readText()
        assertTrue(text.contains("--force-confdef"))
        assertTrue(text.contains("--force-confold"))
    }

    @Test fun theAnswerIsGivenAgainWhenTheGuestIsMissingIt() {
        val root = rootWith("usr/bin/apt")
        val config = File(root, "etc/apt/apt.conf.d/99droidhost")
        config.parentFile?.mkdirs()
        config.writeText("DPkg::Use-Pty \"false\";\n")
        VmGuest.prepare(root)
        assertTrue(config.readText().contains("--force-confold"))
    }

    @Test fun aGuestWithoutAptIsNotGivenAnAptConfig() {
        val root = rootWith("usr/bin/pacman")
        VmGuest.prepare(root)
        assertFalse(File(root, "etc/apt").exists())
    }

    @Test fun pacmanIsToldTheDownloadSandboxCannotWork() {
        // The sandbox pacman builds with landlock and seccomp cannot be set up under proot.
        val root = rootWith("usr/bin/pacman")
        val conf = File(root, "etc/pacman.conf")
        conf.parentFile?.mkdirs()
        conf.writeText("[options]\nArchitecture = aarch64\n")
        VmGuest.prepare(root)
        assertTrue(conf.readText().contains("DisableSandbox"))
    }

    @Test fun theSandboxSettingIsNotDoubled() {
        val root = rootWith("usr/bin/pacman")
        val conf = File(root, "etc/pacman.conf")
        conf.parentFile?.mkdirs()
        conf.writeText("[options]\nDisableSandbox\n")
        VmGuest.prepare(root)
        assertEquals(1, Regex("DisableSandbox").findAll(conf.readText()).count())
    }

    @Test fun theSandboxSwitchIsSplitWhenTheConfigurationNamesTheHalves() {
        // pacman 7 carries the switches commented out, and reads two halves rather than the
        // single switch of pacman 6. A commented line is not a setting.
        val root = rootWith("usr/bin/pacman")
        val conf = File(root, "etc/pacman.conf")
        conf.parentFile?.mkdirs()
        conf.writeText("[options]\n#DisableSandboxFilesystem\n#DisableSandboxSyscalls\nArchitecture = aarch64\n")
        VmGuest.prepare(root)
        val text = conf.readText()
        assertTrue(Regex("(?m)^DisableSandboxFilesystem$").containsMatchIn(text))
        assertTrue(Regex("(?m)^DisableSandboxSyscalls$").containsMatchIn(text))
        assertFalse(Regex("(?m)^DisableSandbox$").containsMatchIn(text))
    }

    @Test fun theSplitSandboxSwitchesAreNotDoubled() {
        val root = rootWith("usr/bin/pacman")
        val conf = File(root, "etc/pacman.conf")
        conf.parentFile?.mkdirs()
        conf.writeText("[options]\nDisableSandboxFilesystem\nDisableSandboxSyscalls\n")
        VmGuest.prepare(root)
        val text = conf.readText()
        assertEquals(1, Regex("(?m)^DisableSandboxFilesystem$").findAll(text).count())
        assertEquals(1, Regex("(?m)^DisableSandboxSyscalls$").findAll(text).count())
        assertFalse(Regex("(?m)^DisableSandbox$").containsMatchIn(text))
    }

    @Test fun theDownloadUserIsCommentedOut() {
        // proot cannot become another user, so the download must run as the guest's root.
        val root = rootWith("usr/bin/pacman")
        val conf = File(root, "etc/pacman.conf")
        conf.parentFile?.mkdirs()
        conf.writeText("[options]\nDownloadUser = alpm\n")
        VmGuest.prepare(root)
        val text = conf.readText()
        assertTrue(text.contains("#DownloadUser = alpm"))
        assertFalse(Regex("(?m)^DownloadUser").containsMatchIn(text))
    }

    @Test fun theDownloadUserIsNotCommentedTwice() {
        val root = rootWith("usr/bin/pacman")
        val conf = File(root, "etc/pacman.conf")
        conf.parentFile?.mkdirs()
        conf.writeText("[options]\nDownloadUser = alpm\n")
        VmGuest.prepare(root)
        VmGuest.prepare(root)
        assertEquals(1, Regex("DownloadUser").findAll(conf.readText()).count())
    }

    @Test fun hooksThatWantAnInitAreMasked() {
        val root = rootWith("usr/bin/pacman")
        val hooks = File(root, "usr/share/libalpm/hooks")
        hooks.mkdirs()
        File(hooks, "30-systemd-tmpfiles.hook").writeText(
            "[Trigger]\nType = Package\nTarget = *\n\n[Action]\nExec = /usr/bin/systemd-tmpfiles --create\n")
        File(hooks, "90-mkinitcpio-install.hook").writeText(
            "[Trigger]\nType = Package\nTarget = linux\n\n[Action]\nExec = /usr/bin/mkinitcpio -P\n")
        File(hooks, "20-harmless.hook").writeText(
            "[Trigger]\nType = Package\nTarget = *\n\n[Action]\nExec = /usr/bin/echo done\n")
        VmGuest.prepare(root)
        val overrides = File(root, "etc/pacman.d/hooks")
        // libalpm 16 fails the transaction on a line it cannot parse, so the mask is empty.
        assertEquals("", File(overrides, "30-systemd-tmpfiles.hook").readText())
        assertTrue(File(overrides, "90-mkinitcpio-install.hook").isFile)
        assertFalse(File(overrides, "20-harmless.hook").exists())
    }

    @Test fun theMaskOfAnEarlierBuildIsReplaced() {
        // An earlier build wrote "/dev/null" as the mask's body; libalpm 16 reads that as an
        // invalid option and fails the whole transaction.
        val root = rootWith("usr/bin/pacman")
        val hooks = File(root, "usr/share/libalpm/hooks")
        hooks.mkdirs()
        File(hooks, "30-systemd-tmpfiles.hook").writeText("[Action]\nExec = /usr/bin/systemd-tmpfiles --create\n")
        val override = File(root, "etc/pacman.d/hooks/30-systemd-tmpfiles.hook")
        override.parentFile?.mkdirs()
        override.writeText("/dev/null")
        VmGuest.prepare(root)
        assertEquals("", override.readText())
    }

    @Test fun aHookMaskedByTheDistributionIsLeftAlone() {
        val root = rootWith("usr/bin/pacman")
        val hooks = File(root, "usr/share/libalpm/hooks")
        hooks.mkdirs()
        File(hooks, "30-systemd-tmpfiles.hook").writeText("[Action]\nExec = /usr/bin/systemd-tmpfiles --create\n")
        val override = File(root, "etc/pacman.d/hooks/30-systemd-tmpfiles.hook")
        override.parentFile?.mkdirs()
        override.writeText("[Action]\nExec = /usr/share/droiddeck/pacman/tmpfiles\n")
        VmGuest.prepare(root)
        assertTrue(override.readText().contains("droiddeck"))
    }
}
