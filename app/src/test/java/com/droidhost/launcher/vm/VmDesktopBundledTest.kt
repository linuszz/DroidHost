package com.droidhost.launcher.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VmDesktopBundledTest {
    @get:Rule val folder = TemporaryFolder()

    private val layout = "usr/share/plasma/look-and-feel/com.droidhost.desktop/contents/layouts/org.kde.plasma.desktop-layout.js"
    private val kickoff = "usr/share/plasma/look-and-feel/com.droidhost.desktop/contents/plasmoidsetupscripts/org.kde.plasma.kickoff.js"
    private val sudo = "usr/local/bin/sudo"
    private val root = "usr/local/bin/droiddeck-root"
    private val polkitd = "usr/local/bin/droiddeck-polkitd"
    private val compositor = "usr/share/droiddeck/kwin-compositor"
    private val globals = "etc/xdg/kdeglobals"
    private val lookAndFeel = "usr/share/plasma/look-and-feel/com.droidhost.desktop"

    @Test fun theBundledSettingsReachTheMachineAheadOfTheRuntimesOwn() {
        val context = RuntimeEnvironment.getApplication()
        val root = folder.newFolder("rootfs")
        val old = File(root, kickoff)
        old.parentFile?.mkdirs()
        old.writeText("applet.writeConfig(\"icon\", \"distributor-logo-steamdeck\")\n")
        assertEquals(31, VmDesktop.copyBundledSettings(context, root))
        assertTrue(old.readText().contains("start-here-kde"))
        assertFalse(old.readText().contains("distributor-logo-steamdeck"))
        val layoutCopy = File(root, layout).readText()
        assertTrue(layoutCopy.contains("start-here-kde"))
        assertFalse(layoutCopy.contains("distributor-logo-steamdeck"))
        assertFalse(layoutCopy.contains("steamdeck-steam.desktop"))
    }

    @Test fun theRuntimesOldCopyDoesNotOverwriteTheBundledOne() {
        val context = RuntimeEnvironment.getApplication()
        val shared = File(context.filesDir, "linuxfs")
        File(shared, kickoff).apply {
            parentFile?.mkdirs()
            writeText("the runtime's old copy: distributor-logo-steamdeck\n")
        }
        val root = folder.newFolder("rootfs")
        assertTrue(VmDesktop.copySettings(context, root) >= 2)
        assertTrue(File(root, kickoff).readText().contains("start-here-kde"))
    }

    @Test fun theBundledCopiesMatchTheOnesTheForkBuildsItsRuntimeWith() {
        val list = File("src/main/assets/droidhost/vm/desktop-files.txt").readText().lines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        for (name in list) {
            if (name == sudo || name == root) continue
            val asset = File("src/main/assets/droidhost/vm/desktop-files/$name")
            val overlay = File("../tools/ubuntu/overlay-desktop/$name")
            assertTrue("missing asset $name", asset.isFile)
            assertTrue("missing overlay $name", overlay.isFile)
            assertEquals(overlay.readText().replace("\r\n", "\n"), asset.readText().replace("\r\n", "\n"))
        }
    }

    @Test fun theListNamesOnlyFilesTheForkCarries() {
        val list = File("src/main/assets/droidhost/vm/desktop-files.txt").readText().lines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(listOf(
            layout, kickoff, sudo, root,
            "etc/xdg/baloofilerc",
            "etc/xdg/fastfetch/config.jsonc",
            "etc/xdg/gtk-3.0/settings.ini",
            "etc/xdg/kcminputrc",
            "etc/xdg/kded6rc",
            "etc/xdg/kdeglobals",
            "etc/xdg/kscreenlockerrc",
            "etc/xdg/ksmserverrc",
            "etc/xdg/ksplashrc",
            "etc/xdg/kwalletrc",
            "etc/xdg/kwinrc",
            "etc/xdg/kwinrulesrc",
            "etc/xdg/plasmarc",
            "etc/xdg/powerdevilrc",
            "etc/xdg/startkderc",
            polkitd,
            "usr/share/dbus-1/services/org.bluez.obex.service",
            "usr/share/droiddeck/fastfetch/logo.txt",
            compositor,
            "usr/share/droiddeck/system-services/org.freedesktop.PackageKit.service",
            "usr/share/droiddeck/system-services/org.freedesktop.PolicyKit1.service",
            "usr/share/licenses/droiddeck-desktop/NOTICE",
            "$lookAndFeel/metadata.json",
            "$lookAndFeel/contents/defaults",
            "$lookAndFeel/contents/plasmoidsetupscripts/org.kde.plasma.folder.js",
            "$lookAndFeel/contents/plasmoidsetupscripts/org.kde.plasma.systemtray.js",
            "usr/share/plasma/shells/org.kde.plasma.desktop/contents/updates/droiddeck-text-editor.js",
        ), list)
        for (name in list) assertTrue(File("src/main/assets/droidhost/vm/desktop-files/$name").isFile)
    }

    @Test fun theCompositorMarkerSelectsTheGpuPath() {
        // KWin 6.8 dropped the QPainter compositor; the marker is what tells the launcher to
        // composite through Zink on the machine's own Vulkan driver instead of software.
        val asset = File("src/main/assets/droidhost/vm/desktop-files/$compositor")
        assertEquals("opengl", asset.readText().replace("\r\n", "\n").trim())
    }

    @Test fun theGlobalsNameTheForksLookAndFeel() {
        val asset = File("src/main/assets/droidhost/vm/desktop-files/$globals")
        assertTrue(asset.readText().contains("LookAndFeelPackage=com.droidhost.desktop"))
    }

    @Test fun theFittedMachineGetsACompleteLookAndFeelAndItsServices() {
        val context = RuntimeEnvironment.getApplication()
        val rootfs = folder.newFolder("rootfs")
        VmDesktop.copyBundledSettings(context, rootfs)
        for (name in listOf("$lookAndFeel/metadata.json", "$lookAndFeel/contents/defaults",
            "$lookAndFeel/contents/plasmoidsetupscripts/org.kde.plasma.folder.js",
            "$lookAndFeel/contents/plasmoidsetupscripts/org.kde.plasma.systemtray.js",
            layout, kickoff)) {
            assertTrue("look-and-feel incomplete: $name", File(rootfs, name).isFile)
        }
        val authority = File(rootfs, polkitd)
        assertTrue("the guest has no polkit authority", authority.isFile)
        assertTrue("the polkit authority cannot run", authority.canExecute())
        val service = File(rootfs, "usr/share/droiddeck/system-services/org.freedesktop.PolicyKit1.service")
        assertTrue(service.readText().contains("Exec=/usr/local/bin/droiddeck-polkitd"))
        assertTrue(File(rootfs, compositor).isFile)
    }

    @Test fun theGuestGetsASudoThatHandsTheCommandToTheAppsRootService() {
        val context = RuntimeEnvironment.getApplication()
        val rootfs = folder.newFolder("rootfs")
        VmDesktop.copyBundledSettings(context, rootfs)
        val wrapper = File(rootfs, sudo)
        assertTrue("the guest has no sudo of its own", wrapper.isFile)
        assertTrue("the guest's sudo cannot be run", wrapper.canExecute())
        val text = wrapper.readText()
        assertTrue("the guest's sudo does not hand the command over", text.contains("droiddeck-root"))
    }

    @Test fun theGuestsRootClientSaysWhichMachineItBelongsTo() {
        val client = File("src/main/assets/droidhost/vm/desktop-files/$root").readText()
        assertTrue("the client does not read the target", client.contains("root-target"))
        assertTrue("the client does not tell the app the machine", client.contains("\"machine\""))
    }

    @Test fun theBundledSudoMatchesTheOneTheForkBuildsItsRuntimeWith() {
        val asset = File("src/main/assets/droidhost/vm/desktop-files/$sudo")
        val overlay = File("../tools/ubuntu/overlay-runtime/usr/local/bin/sudo")
        assertTrue("missing asset $sudo", asset.isFile)
        assertTrue("missing overlay $sudo", overlay.isFile)
        assertEquals(overlay.readText().replace("\r\n", "\n"), asset.readText().replace("\r\n", "\n"))
    }
}
