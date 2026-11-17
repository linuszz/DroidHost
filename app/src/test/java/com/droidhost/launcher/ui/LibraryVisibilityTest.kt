package com.droidhost.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droidhost.launcher.frontend.Library
import com.droidhost.launcher.session.SessionPrefs
import com.droidhost.launcher.stores.CatalogItem
import com.droidhost.launcher.stores.CredentialCipher
import com.droidhost.launcher.stores.Store
import com.droidhost.launcher.stores.StoreAccounts
import com.droidhost.launcher.stores.StoresState
import com.droidhost.launcher.stores.xbox.XboxLibraryFilter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class LibraryVisibilityTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val ctx get() = RuntimeEnvironment.getApplication()
    private var generation by mutableIntStateOf(0)

    @Before fun prepare() {
        StoreAccounts.keys = CredentialCipher.KeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        StoreAccounts.keystoreFailed = false
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
        LibraryView.source = LIBRARY_ALL
        LibraryView.query = ""
        LibraryView.focusFirst = false
        StoresState.library.clear()
        StoresState.accounts.clear()
        for (store in listOf(Store.GOG, Store.EPIC)) {
            StoreAccounts.write(ctx, store, JSONObject().put("access_token", "test-only").put("display_name", "Library test"))
            StoresState.accounts[store] = "Library test"
            StoresState.library[store] = listOf(CatalogItem(store, "owned", "Uninstalled ${store.shortLabel}", null, owned = true, sizeBytes = 1024))
        }
    }

    @After fun clear() {
        Steps.drop()
        XboxLibraryFilter.set(ctx, XboxLibraryFilter.ALL)
        for (store in listOf(Store.GOG, Store.EPIC)) StoreAccounts.clear(ctx, store)
        StoreAccounts.keys = CredentialCipher.Keystore
        StoreAccounts.keystoreFailed = false
        StoresState.library.clear()
        StoresState.accounts.clear()
        ctx.getSharedPreferences("session", 0).edit().clear().commit()
        LibraryView.source = LIBRARY_ALL
        LibraryView.query = ""
        LibraryView.focusFirst = false
    }

    @Test fun addedGamesStayVisibleAndTheFilterChoiceSurvivesReopeningLibrary() {
        show()
        compose.onNodeWithText("Added game").assertExists()
        compose.onNodeWithText("Steam client game").assertDoesNotExist()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        chooseVisibility(true)
        assertTrue(SessionPrefs.libraryShowUninstalled(ctx))
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        compose.onNodeWithText("Added game").assertExists()
        compose.runOnIdle { generation++; Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithContentDescription("Filter · All owned games").assertExists()
        compose.onNodeWithText("Added game").assertExists()
        chooseVisibility(false)
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        compose.onNodeWithText("Added game").assertExists()
    }

    @Test fun theFilterExpandsBesideSortWithoutChangingTheSelectionOnOpen() {
        show()
        val filter = compose.onNodeWithContentDescription("Filter · Installed only")
        val sort = compose.onNodeWithContentDescription("Sort")
        assertTrue(filter.fetchSemanticsNode().boundsInRoot.right <= sort.fetchSemanticsNode().boundsInRoot.left)
        filter.performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Installed only").assertIsDisplayed()
        compose.onNodeWithText("All owned games").assertIsDisplayed()
        compose.onAllNodesWithText("Filter").assertCountEquals(1)
        compose.onNodeWithText("Added game").assertExists()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        compose.runOnIdle { Steps.fold(); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1_000)
        sort.performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onAllNodesWithText("Sort").assertCountEquals(1)
        compose.onNodeWithText("Recently played").assertIsDisplayed()
    }

    @Test fun theXboxAccessChoicesShareTheLibraryFilter() {
        LibraryView.source = Store.XBOX.id
        show()
        compose.onNodeWithContentDescription("Filter · Installed only").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Installed only").assertIsDisplayed()
        compose.onNodeWithText("All owned games").assertIsDisplayed()
        compose.onNodeWithText("Game Pass").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(XboxLibraryFilter.GAME_PASS, XboxLibraryFilter.current)
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
    }

    @Test fun aHiddenStoreRemainsReachableInLibraryInItsConfiguredOrder() {
        SessionPrefs.setStoresTabOrder(ctx, listOf("epic", "gog"))
        SessionPrefs.setStoresTabHidden(ctx, setOf("gog"))
        show()
        compose.onNodeWithTag("library-source-steam").assertDoesNotExist()
        val epic = compose.onNodeWithTag("library-source-epic").fetchSemanticsNode().boundsInRoot.left
        val gog = compose.onNodeWithTag("library-source-gog").fetchSemanticsNode().boundsInRoot.left
        assertTrue(epic < gog)
        compose.onNodeWithTag("library-source-gog").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Added GOG game").assertExists()
        compose.onNodeWithText("Added Epic game").assertDoesNotExist()
        compose.onNodeWithText("Added game").assertDoesNotExist()
    }

    @Test fun theStoresOwnedGamesLinkShowsThatStoresAddedGamesDespiteTheSavedInstalledOnlyView() {
        assertFalse(SessionPrefs.libraryShowUninstalled(ctx))
        LibraryView.showOwned(ctx, Store.GOG)
        show()
        compose.onNodeWithText("Added GOG game").assertExists()
        compose.onNodeWithText("Added game").assertDoesNotExist()
        compose.onNodeWithText("Uninstalled GOG").assertDoesNotExist()
        assertTrue(SessionPrefs.libraryShowUninstalled(ctx))
    }

    @Test fun theStoresOwnedGamesLinkUpdatesALibraryPageThatIsStillComposed() {
        LibraryView.source = Store.GOG.id
        show()
        compose.onNodeWithContentDescription("Filter · Installed only").assertExists()
        compose.onNodeWithText("Added GOG game").assertExists()
        compose.runOnIdle { LibraryView.showOwned(ctx, Store.GOG); Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithContentDescription("Filter · All owned games").assertExists()
        compose.onNodeWithText("Added GOG game").assertExists()
        assertTrue(SessionPrefs.libraryShowUninstalled(ctx))
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { key(generation) {
            DroidHostTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost { Box(Modifier.fillMaxSize()) {
                        LibraryPage(state(), actions(), "games", {}, Modifier.fillMaxSize())
                        StepsLayer(Offset.Zero)
                    } }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun chooseVisibility(show: Boolean) {
        compose.onNodeWithContentDescription("Filter · " + if (SessionPrefs.libraryShowUninstalled(ctx)) "All owned games" else "Installed only")
            .performClick()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(Steps.open)
        compose.onNodeWithText(if (show) "All owned games" else "Installed only")
            .performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(show, SessionPrefs.libraryShowUninstalled(ctx))
    }

    private fun state() = FrontEndState(
        installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
        romsDir = null, logsEnabled = false,
        // The library only renders manually-added entries; a store-linked one keeps its store as source.
        steamGames = listOf(
            Library.SteamGame(70, "Steam client game", null, "internal"),
            Library.SteamGame(9000, "Added game", null, Library.ADDED, source = Library.ADDED),
            Library.SteamGame(9001, "Added GOG game", null, Library.ADDED, source = Store.GOG.id, storeId = "owned"),
            Library.SteamGame(9002, "Added Epic game", null, Library.ADDED, source = Store.EPIC.id, storeId = "owned"),
            Library.SteamGame(9003, "Added Xbox game", null, Library.ADDED, source = Store.XBOX.id, storeId = "owned"),
        ),
        emulators = emptyList(), running = null,
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
        onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
        onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> },
    )
}
