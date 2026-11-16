package com.droidhost.launcher.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmGuestTest {
    @get:Rule val folder = TemporaryFolder()

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
        val root = folder.newFolder("rootfs")
        VmGuest.prepare(root)
        val config = File(root, "etc/apt/apt.conf.d/99droidhost")
        assertTrue(config.isFile)
        assertTrue(config.readText().contains("DPkg::Use-Pty"))
    }

    @Test fun theGuestIsToldHowDpkgShouldAnswerAConffile() {
        // dpkg asks a question when a package brings a configuration file that the machine
        // already has another version of.  There is no terminal to answer it in, so the answer
        // is given to dpkg instead of waited for.
        val root = folder.newFolder("rootfs")
        VmGuest.prepare(root)
        val text = File(root, "etc/apt/apt.conf.d/99droidhost").readText()
        assertTrue(text.contains("--force-confdef"))
        assertTrue(text.contains("--force-confold"))
    }

    @Test fun theAnswerIsGivenAgainWhenTheGuestIsMissingIt() {
        val root = folder.newFolder("rootfs")
        val config = File(root, "etc/apt/apt.conf.d/99droidhost")
        config.parentFile?.mkdirs()
        config.writeText("DPkg::Use-Pty \"false\";\n")
        VmGuest.prepare(root)
        assertTrue(config.readText().contains("--force-confold"))
    }
}
