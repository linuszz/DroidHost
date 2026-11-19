package com.droidhost.launcher.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FlatpakDesktopTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File

    @Before fun reset() {
        ctx.getSharedPreferences("flatpak", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
        VmRuntime.machinesDir(ctx).deleteRecursively()
        LinuxRuntime.rootDir(ctx).deleteRecursively()
        root = LinuxRuntime.rootDir(ctx).apply { mkdirs() }
    }

    @Test fun aFlatpakHostIsAuthoritativeEvenWhenNullMeansTheSharedRuntime() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        VmRuntime.allocateRootfs(ctx, machine.id)
        assertNull(FlatpakDesktop.desktopMachineId(ctx, null, "org.example.App"))
        assertEquals(machine.id, FlatpakDesktop.desktopMachineId(ctx, machine.id, "org.example.App"))
    }

    @Test fun aPlainDesktopKeepsTheFirstMachineFallback() {
        assertNull(FlatpakDesktop.desktopMachineId(ctx, null, null))
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        assertEquals(machine.id, FlatpakDesktop.desktopMachineId(ctx, null, null))
        assertEquals(machine.id, FlatpakDesktop.desktopMachineId(ctx, machine.id, null))
    }

    @Test fun coldStartWritesAOneShotAutostartAndEnablesTheWatcher() {
        FlatpakDesktop.prepareSession(root, "org.example.App", listOf("org.example.App"), "Example")
        val entry = File(root, "root/.config/autostart/droidhost-flatpak-org.example.App.desktop")
        assertTrue(entry.isFile)
        val text = entry.readText()
        assertTrue(text.contains("Name=Example"))
        assertTrue(text.contains("Exec=/bin/bash -c \"rm -f \$HOME/.config/autostart/droidhost-flatpak-org.example.App.desktop; exec /usr/local/bin/droiddeck-flatpak-run org.example.App\""))
        assertTrue(text.contains("X-KDE-autostart-phase=2"))
        assertTrue(File(root, "root/.droiddeck-remote-run").isFile)
    }

    @Test fun foreignArchArgsReachTheAutostartCommand() {
        FlatpakDesktop.prepareSession(root, "org.example.App", listOf("--arch=x86_64", "org.example.App"), null)
        val entry = File(root, "root/.config/autostart/droidhost-flatpak-org.example.App.desktop")
        assertTrue(entry.readText().contains("exec /usr/local/bin/droiddeck-flatpak-run --arch=x86_64 org.example.App"))
    }

    @Test fun aPlainDesktopSessionEnablesTheWatcherWithoutAnAutostart() {
        FlatpakDesktop.prepareSession(root, null, emptyList(), null)
        assertTrue(File(root, "root/.droiddeck-remote-run").isFile)
        assertNull(File(root, "root/.config/autostart").listFiles()?.firstOrNull { it.name.startsWith("droidhost-flatpak-") })
    }

    @Test fun aNewSessionClearsStaleOneShotsAndQueuedScriptsButKeepsOtherAutostarts() {
        val stale = File(root, "root/.config/autostart/droidhost-flatpak-org.old.App.desktop").apply { parentFile?.mkdirs(); writeText("stale") }
        val other = File(root, "root/.config/autostart/other.desktop").apply { writeText("keep") }
        val queued = File(root, "root/.cache/droiddeck/run/flatpak-old.sh").apply { parentFile?.mkdirs(); writeText("stale") }
        FlatpakDesktop.prepareSession(root, null, emptyList(), null)
        assertFalse(stale.exists())
        assertFalse(queued.exists())
        assertTrue(other.isFile)
    }

    @Test fun warmStartQueuesAScriptThatWaitsForTheDesktopSocket() {
        assertTrue(FlatpakDesktop.queueLaunch(root, "org.example.App", listOf("org.example.App")))
        val scripts = File(root, "root/.cache/droiddeck/run").listFiles().orEmpty().filter { it.name.endsWith(".sh") }
        assertEquals(1, scripts.size)
        val text = scripts.single().readText()
        assertTrue(text.contains("wayland-1"))
        assertTrue(text.contains("exec /usr/local/bin/droiddeck-flatpak-run org.example.App"))
    }

    @Test fun twoWarmLaunchesQueueTwoScripts() {
        assertTrue(FlatpakDesktop.queueLaunch(root, "org.example.App", listOf("org.example.App")))
        assertTrue(FlatpakDesktop.queueLaunch(root, "org.example.Other", listOf("org.example.Other")))
        assertEquals(2, File(root, "root/.cache/droiddeck/run").listFiles().orEmpty().count { it.name.endsWith(".sh") })
    }

    @Test fun malformedIdsAndArgsNeverReachTheShell() {
        assertFalse(FlatpakDesktop.queueLaunch(root, "../escape", listOf("../escape")))
        assertFalse(FlatpakDesktop.queueLaunch(root, "org.example.App", listOf("org.example.App; rm -rf /")))
        FlatpakDesktop.prepareSession(root, "../escape", listOf("../escape"), null)
        FlatpakDesktop.prepareSession(root, "org.example.App", listOf("$(reboot)"), null)
        assertNull(File(root, "root/.config/autostart").listFiles()?.firstOrNull { it.name.startsWith("droidhost-flatpak-") })
        assertEquals(0, File(root, "root/.cache/droiddeck/run").listFiles().orEmpty().size)
    }
}
