package com.droidhost.launcher.ui

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.droidhost.launcher.R
import com.droidhost.launcher.input.ControllerPrefs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Batch E: the guest UI scale becomes a global row under display, and the settings that only
 * land after a restart say so (design §4.3 / §4.6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsReorderRowsTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun displayPageOffersTheGuestUiScaleAndSaysItNeedsARestart() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalNarrowPane provides false) {
                    FocusGlideHost { SetupPanel(state(context), actions(), {}, {}, {}) }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.setup_tab_session)).performClick()
        compose.onNodeWithText(context.getString(R.string.quick_ui_scale)).assertExists()
        compose.onNodeWithText(context.getString(R.string.setup_tag_needs_restart), ignoreCase = true).assertExists()
    }

    @Test fun advancedPageNamesTheContainerEnvironmentAndSaysItNeedsARestart() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalNarrowPane provides false) {
                    FocusGlideHost { SetupPanel(state(context), actions(), {}, {}, {}) }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.setup_tab_diagnostics)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.setup_container_env)).assertExists()
        compose.onNodeWithText(context.getString(R.string.setup_tag_needs_restart), ignoreCase = true).assertExists()
    }

    private fun state(context: Context) = FrontEndState(
        installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "",
        romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
        controller = ControllerPrefs.read(context),
    )

    private fun actions() = FrontEndActions(
        onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
        onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
        onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
        onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
        onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
        onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> },
        onSetPhantomProcessLimit = { _, _ -> }, controller = ControllerActions({}),
    )
}
