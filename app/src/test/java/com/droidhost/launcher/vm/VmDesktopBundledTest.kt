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

    private val layout = "usr/share/plasma/look-and-feel/com.valve.vapor.desktop/contents/layouts/org.kde.plasma.desktop-layout.js"
    private val kickoff = "usr/share/plasma/look-and-feel/com.valve.vapor.desktop/contents/plasmoidsetupscripts/org.kde.plasma.kickoff.js"

    @Test fun theBundledSettingsReachTheMachineAheadOfTheRuntimesOwn() {
        val context = RuntimeEnvironment.getApplication()
        val root = folder.newFolder("rootfs")
        val old = File(root, kickoff)
        old.parentFile?.mkdirs()
        old.writeText("applet.writeConfig(\"icon\", \"distributor-logo-steamdeck\")\n")
        assertEquals(2, VmDesktop.copyBundledSettings(context, root))
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

    @Test fun theWallpaperGoesToTheMachineUnderTheForksName() {
        val context = RuntimeEnvironment.getApplication()
        val shared = File(context.filesDir, "linuxfs")
        val oldName = "usr/share/wallpapers/DroidDeck/contents/images/1920x1080.svg"
        File(shared, oldName).apply {
            parentFile?.mkdirs()
            writeText("<svg/>\n")
        }
        val root = folder.newFolder("rootfs")
        VmDesktop.copySettings(context, root)
        assertTrue(File(root, "usr/share/wallpapers/DroidHost/contents/images/1920x1080.svg").isFile)
        assertFalse(File(root, oldName).exists())
    }

    @Test fun theBundledCopiesMatchTheOnesTheForkBuildsItsRuntimeWith() {
        for (name in listOf(layout, kickoff)) {
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
        assertEquals(listOf(layout, kickoff), list)
        for (name in list) assertTrue(File("src/main/assets/droidhost/vm/desktop-files/$name").isFile)
    }
}
