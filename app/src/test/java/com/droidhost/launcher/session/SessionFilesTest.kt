package com.droidhost.launcher.session

import com.droidhost.launcher.BuildConfig
import com.droidhost.launcher.runtime.DesktopCatalog
import com.droidhost.launcher.runtime.DesktopPack
import com.droidhost.launcher.runtime.DesktopRemoval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SessionFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun compositor(root: File) = write(root, "usr/bin/kwin_wayland", "#!/bin/sh\n")

    private fun fittedImport(root: File) =
        write(root, "usr/share/droiddeck/desktop-installed", "installed 1\n")

    @Test fun anEmptyRootfsWantsNoDesktopFiles() {
        assertFalse(SessionFiles.wantsDesktopFiles(folder.newFolder("rootfs")))
    }

    @Test fun aFittedImportCarriesItsOwnMarkerAndWantsTheFiles() {
        val root = folder.newFolder("rootfs")
        fittedImport(root)
        assertTrue(SessionFiles.wantsDesktopFiles(root))
    }

    @Test fun aCompositorWithoutAMarkerIsNotADeclaredDesktop() {
        val root = folder.newFolder("rootfs")
        compositor(root)
        assertFalse(SessionFiles.wantsDesktopFiles(root))
    }

    @Test fun thePackMarkerWithTheCurrentTagWantsTheFiles() {
        val root = folder.newFolder("rootfs")
        compositor(root)
        write(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}", BuildConfig.DESKTOP_KDE_TAG)
        assertTrue(SessionFiles.wantsDesktopFiles(root))
    }

    @Test fun theTagFromTheMachinesOwnPackJsonIsHonoured() {
        val root = folder.newFolder("rootfs")
        compositor(root)
        write(root, DesktopPack.INFO,
            "{\"tag\":\"custom-r9\",\"url\":\"https://example.invalid/pack.tar\",\"sha256\":\"0\",\"size\":1}")
        write(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}", "custom-r9")
        assertTrue(SessionFiles.wantsDesktopFiles(root))
        write(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}", "stale-r8")
        assertFalse(SessionFiles.wantsDesktopFiles(root))
    }

    @Test fun aRemovalInProgressStopsThePackPath() {
        val root = folder.newFolder("rootfs")
        compositor(root)
        write(root, ".droiddeck-pkg-${DesktopCatalog.DESKTOP_ID}", BuildConfig.DESKTOP_KDE_TAG)
        write(root, DesktopRemoval.PENDING, "")
        assertFalse(SessionFiles.wantsDesktopFiles(root))
    }

    @Test fun aRemovalInProgressDoesNotHideAFittedImport() {
        val root = folder.newFolder("rootfs")
        fittedImport(root)
        write(root, DesktopRemoval.PENDING, "")
        assertTrue(SessionFiles.wantsDesktopFiles(root))
    }
}
