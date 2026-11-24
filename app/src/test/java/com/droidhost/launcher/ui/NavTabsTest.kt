package com.droidhost.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class NavTabsTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before @After fun reset() {
        ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        NavTabs.load(ctx)
        com.droidhost.launcher.agent.AgentTab.load(ctx)
    }

    @Test fun shelvedFeaturesStayOutOfTheBarOnAFreshInstall() {
        assertEquals(listOf("desktop", "setup", "system"), navigationKeys(state()))
        assertEquals(emptyList<String>(), NavTabs.more(state()))
        assertEquals(setOf("agent"), NavTabs.shelved)
        assertTrue(com.droidhost.launcher.agent.AgentTab.shown)
    }

    @Test fun shelvingATabOnceKeepsItShelvedAfterReload() {
        place("system", TabPlace.MORE)
        NavTabs.load(ctx)
        assertEquals(listOf("system"), NavTabs.more(state()))
    }

    @Test fun shelvedTabsLeaveTheBarAndAddMoreAtTheEnd() {
        place("system", TabPlace.MORE)
        place("setup", TabPlace.MORE)
        val s = state()
        assertEquals(listOf("setup", "system"), NavTabs.more(s))
        assertEquals(NavTabs.MORE, navigationKeys(s).last())
        assertFalse("system" in navigationKeys(s))
    }

    @Test fun hiddenTabsAreNowhereAndSetupAndSystemCannotBeHidden() {
        place("desktop", TabPlace.HIDDEN)
        place("setup", TabPlace.HIDDEN)
        place("system", TabPlace.HIDDEN)
        val s = state()
        assertFalse("desktop" in navigationKeys(s))
        assertFalse("desktop" in NavTabs.more(s))
        assertTrue("setup" in navigationKeys(s))
        assertTrue("system" in navigationKeys(s))
    }

    @Test fun aDisabledFeatureReadsAsHidden() {
        com.droidhost.launcher.agent.AgentTab.set(ctx, false)
        assertEquals(TabPlace.HIDDEN, NavTabs.place(state(), "agent"))
    }

    @Test fun movingSwapsWithTheNextVisibleTabAndPersists() {
        moveTab("setup", -1)
        assertEquals(listOf("setup", "desktop", "system"), navigationKeys(state()))
        NavTabs.load(ctx)
        assertEquals(listOf("setup", "desktop", "system"), navigationKeys(state()))
    }

    @Test fun tabsMissingFromASavedOrderSlotInAfterTheirDefaultNeighbour() {
        assertEquals(listOf("desktop", "android-apps", "setup", "system"),
            NavTabs.arrange(listOf("updates", "steam")))
        assertEquals(listOf("setup", "system", "desktop", "android-apps"),
            NavTabs.arrange(listOf("games", "steam", "setup", "stores", "desktop", "components", "android-apps", "updates", "bogus")))
    }

    @Test fun moreCarriesTheBadgeOfAShelvedTabThatNeedsAttention() {
        place("setup", TabPlace.MORE)
        assertTrue(railBadge(state(phantomProcessStatus = com.droidhost.launcher.core.PhantomProcessStatus.ENABLED), NavTabs.MORE))
        place("setup", TabPlace.BAR)
        assertFalse(railBadge(state(phantomProcessStatus = com.droidhost.launcher.core.PhantomProcessStatus.ENABLED), NavTabs.MORE))
    }

    @Test fun anUninstalledOptionalRuntimeDoesNotNagOnTheSetupTab() {
        assertFalse(railBadge(state(setupReady = false, installed = null, available = "r10"), "setup"))
    }

    @Test fun aRuntimeWithoutAVersionMarkerDoesNotNagOnTheSetupTab() {
        assertFalse(railBadge(state(setupReady = true, installed = null, available = "r10"), "setup"))
    }

    @Test fun anAvailableRuntimeUpdateDoesNotNagOnTheSetupTab() {
        assertFalse(railBadge(state(installed = "r9", available = "r10"), "setup"))
    }

    @Test fun sideRailOffersMoreWhenATabIsShelved() {
        place("setup", TabPlace.MORE)
        val s = state()
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    Box(Modifier.height(752.dp)) { SideRail(s, "desktop", {}, {}, actions(), Modifier) }
                }
            }
        }
        compose.onNodeWithTag("rail-setup").assertDoesNotExist()
        compose.onNode(hasTestTag("rail-more")).assertIsDisplayed()
    }

    @Test fun morePageListsShelvedTabs() {
        place("setup", TabPlace.MORE)
        place("system", TabPlace.MORE)
        val s = state()
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { Box(Modifier.fillMaxSize()) { MorePage(s, actions(), {}, Modifier.fillMaxSize()) } }
                }
            }
        }
        compose.onNodeWithTag("more-setup").assertIsDisplayed()
        compose.onNodeWithTag("more-system").assertIsDisplayed()
    }

    @Test fun backFromAShelvedTabReturnsToMore() {
        place("desktop", TabPlace.MORE)
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            DroidHostTheme { FrontEndScreen(state(), actions(), page = null) }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("rail-more").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").assertDoesNotExist()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("more-desktop").assertIsDisplayed()
    }

    @Test fun startupWithTheHomeTabHiddenLandsOnARealPane() {
        place("desktop", TabPlace.HIDDEN)
        repeat(NavTabs.ALL.size) { moveTab("system", -1) }
        assertEquals("system", navigationKeys(state()).first())
        compose.mainClock.autoAdvance = false
        compose.setContent { DroidHostTheme { FrontEndScreen(state(), actions(), page = null) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNode(hasText(ctx.getString(com.droidhost.launcher.R.string.content_page_gone))).assertDoesNotExist()
        compose.onNode(hasTestTag("rail-system") and androidx.compose.ui.test.isSelected()).assertExists()
    }

    @Test fun theDesktopPageOffersANewMachineWhenNothingIsInstalled() {
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
        val s = FrontEndState(
            installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
            desktopInstalled = false, desktopPresent = false, offlineAccount = null, offline = false,
            frameGenLabel = "", romsDir = null, logsEnabled = false, steamGames = emptyList(),
            emulators = emptyList(), running = null,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent { DroidHostTheme { FrontEndScreen(s, actions(), page = null) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithContentDescription(ctx.getString(com.droidhost.launcher.R.string.vm_new)).assertExists()
    }

    @Test fun aDesktopAlreadyOnDiskShowsUpAsTheUbuntuMachine() {
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
        compose.mainClock.autoAdvance = false
        compose.setContent { DroidHostTheme { FrontEndScreen(state(), actions(), page = null) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithContentDescription("Ubuntu").assertExists()
    }

    @Test fun setupListMovesATabAcrossTheMoreLineWithTheControllerAndHidesWithItsSwitch() {
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    androidx.compose.foundation.layout.Column(Modifier.height(752.dp).verticalScroll(rememberScrollState())) {
                        TabsSettingsGroup(MenuHost(), state(), actions())
                    }
                }
            }
        }
        val last = navigationKeys(state()).last { it != NavTabs.MORE }
        compose.onNodeWithTag("tab-row-$last").requestFocus()
        compose.onNodeWithTag("tab-row-$last").performKeyPress(pad(android.view.KeyEvent.KEYCODE_BUTTON_Y))
        compose.onNodeWithTag("tab-row-$last").performKeyPress(pad(android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        compose.onNodeWithTag("tab-row-$last").performKeyPress(pad(android.view.KeyEvent.KEYCODE_BUTTON_Y))
        compose.runOnIdle { assertEquals(last, NavTabs.more(state()).first()) }
        compose.onNodeWithTag("tab-shown-desktop").performClick()
        compose.runOnIdle { assertFalse("desktop" in navigationKeys(state())) }
        compose.onNodeWithTag("tab-shown-setup").performClick()
        compose.runOnIdle { assertTrue("setup" in navigationKeys(state())) }
    }

    private fun pad(code: Int) = androidx.compose.ui.input.key.KeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))

    private fun place(key: String, where: TabPlace, s: FrontEndState = state()) {
        NavTabs.setHidden(ctx, key, where == TabPlace.HIDDEN)
        if (where == TabPlace.HIDDEN) return
        val keys = NavTabs.listed(s)
        val more = keys.filter { if (it == key) where == TabPlace.MORE else it in NavTabs.shelved }
        NavTabs.rearrange(ctx, s, keys - more.toSet(), more)
    }

    private fun moveTab(key: String, step: Int, s: FrontEndState = state()) {
        val keys = NavTabs.listed(s)
        val rows = moveTabRow(tabRows(keys.filter { it !in NavTabs.shelved }, keys.filter { it in NavTabs.shelved }), key, step) ?: return
        val split = rows.indexOf(rows.first { it !in keys })
        NavTabs.rearrange(ctx, s, rows.subList(0, split), rows.subList(split + 1, rows.size))
    }

    private fun state(
        storesOn: Boolean = true, downloads: Int = 0, setupReady: Boolean = true, installed: String? = "r9", available: String? = null,
        phantomProcessStatus: com.droidhost.launcher.core.PhantomProcessStatus = com.droidhost.launcher.core.PhantomProcessStatus.NOT_APPLICABLE,
    ) = FrontEndState(
        installed = installed, ready = setupReady, available = available, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
        steamGames = emptyList(), emulators = emptyList(), running = null,
        gameStoresEnabled = storesOn, storeDownloadsActive = downloads,
        phantomProcessStatus = phantomProcessStatus,
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {}, onRom = {}, onResume = {},
        onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {},
        onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
        onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
    )
}
