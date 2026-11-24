package com.droidhost.launcher.ui

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.droidhost.launcher.R
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmMachine
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class VmHomeTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun nothingInstalledOffersANewMachineAndSaysSo() {
        render(state(desktopInstalled = false, desktopPresent = false))
        compose.onNodeWithContentDescription(ctx.getString(R.string.vm_new)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.vm_empty_title)).assertIsDisplayed()
    }

    @Test fun aMachineWithoutTheDesktopAsksForTheInstall() {
        render(state(desktopInstalled = false, desktopPresent = false, machine = true))
        compose.onNodeWithText(ctx.getString(R.string.vm_install)).assertIsDisplayed()
    }

    @Test fun anInstalledMachineOffersTheStart() {
        render(state(desktopInstalled = true))
        compose.onNodeWithText(ctx.getString(R.string.vm_start)).assertIsDisplayed()
    }

    @Test fun aRunningMachineOffersResumeAndAStopIcon() {
        render(state(desktopInstalled = true, sessionRunning = true))
        // The primary action re-enters the live session; stopping moved to the beside icon row.
        compose.onNodeWithText(ctx.getString(R.string.vm_resume)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.vm_stop)).performScrollTo().assertIsDisplayed()
    }

    @Test fun aRemovalInFlightReadsAsDeleting() {
        render(state(desktopInstalled = true, removalPending = true))
        // The chip, the card detail and the button all read the same word.
        assertTrue(compose.onAllNodesWithText(ctx.getString(R.string.vm_deleting)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aBroughtMachineAsksForItsDesktop() {
        render(state(desktopInstalled = false, desktopPresent = false, machine = true, imported = true))
        compose.onNodeWithText(ctx.getString(R.string.vm_install_desktop)).assertIsDisplayed()
    }

    @Test fun aBroughtMachineWithADesktopOffersTheStart() {
        render(state(desktopInstalled = false, desktopPresent = false, machine = true, imported = true,
            desktopInMachine = true, driverInMachine = true))
        compose.onNodeWithText(ctx.getString(R.string.vm_start)).assertIsDisplayed()
    }

    @Test fun aDesktopWithoutTheDriverReadsAsSoftwareRatherThanReady() {
        render(state(desktopInstalled = false, desktopPresent = false, machine = true, imported = true,
            desktopInMachine = true, driverInMachine = false))
        assertTrue(compose.onAllNodesWithText(ctx.getString(R.string.vm_state_degraded)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aDesktopWithTheDriverReadsAsReady() {
        render(state(desktopInstalled = false, desktopPresent = false, machine = true, imported = true,
            desktopInMachine = true, driverInMachine = true))
        assertTrue(compose.onAllNodesWithText(ctx.getString(R.string.vm_state_ready)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun severalMachinesEachGetACard() {
        val distro = VmCatalog.distros.first { it.engine }
        VmStore.create(ctx, distro, "vm-one", imported = true)
        VmStore.create(ctx, distro, "vm-two", imported = true)
        render(state(desktopInstalled = false, desktopPresent = false, machine = false))
        assertTrue(compose.onAllNodesWithContentDescription(distro.name).fetchSemanticsNodes().size == 2)
    }

    @Test fun thePickerListsEverySystemAndGatesTheOnesWithoutAnEngine() {
        render(state(desktopInstalled = false, desktopPresent = false))
        compose.onNodeWithContentDescription(ctx.getString(R.string.vm_new)).performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(ctx.getString(R.string.vm_pick_title)).assertIsDisplayed()
        // The list scrolls, so the lower rows are composed without being on screen.
        for (d in VmCatalog.distros) if (!d.engine) compose.onNodeWithText(d.name).assertExists()
        val gated = compose.onAllNodesWithText(ctx.getString(R.string.vm_needs_engine)).fetchSemanticsNodes().size
        assertTrue("gated=$gated", gated == VmCatalog.distros.count { !it.engine })
    }

    private fun render(s: FrontEndState) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidHostTheme {
                DesktopPage(s, actions(), Modifier, onSelect = {})
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun state(
        desktopInstalled: Boolean,
        desktopPresent: Boolean = desktopInstalled,
        sessionRunning: Boolean = false,
        removalPending: Boolean = false,
        machine: Boolean = desktopInstalled,
        busy: Boolean = false,
        imported: Boolean = false,
        desktopInMachine: Boolean = false,
        driverInMachine: Boolean = false,
    ): FrontEndState {
        if (machine) {
            val distro = VmCatalog.distros.first { it.engine }
            val created = VmStore.create(ctx, distro, if (imported) "vm-brought" else VmMachine.DEFAULT_ID, imported)
            if (imported && created != null) {
                // The machine's own rootfs holds a desktop when the marker is there.
                val root = VmRuntime.allocateRootfs(ctx, created.id)
                if (desktopInMachine) File(root, "usr/share/droiddeck").mkdirs()
                if (desktopInMachine) File(root, "usr/share/droiddeck/desktop-installed").writeText("installed\n")
                if (driverInMachine) File(root, "usr/share/droiddeck/driver-installed").writeText("installed\n")
            }
        }
        return FrontEndState(
            installed = "r9", ready = true, available = null, busy = busy, stage = "", percent = -1,
            desktopInstalled = desktopInstalled, desktopPresent = desktopPresent,
            offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
            steamGames = emptyList(), emulators = emptyList(), running = null,
            sessionRunning = sessionRunning, removalPending = removalPending,
        )
    }

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = { _ -> }, onEmulator = {}, onRom = {}, onResume = {},
        onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {},
        onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
        onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
    )
}
