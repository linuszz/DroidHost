package com.droidhost.launcher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
 * Batch F: the component manager belongs inside the system tab (design §4.4), so the
 * sub-page renders the host-provided panel instead of the old jump-out entry cards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemComponentsPanelTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun componentsSubPageRendersTheInlinePanel() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalNarrowPane provides false) {
                    SystemPage(
                        state(context, inline = true),
                        actions(),
                        Modifier,
                    )
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.system_tab_components)).performScrollTo().performClick()
        compose.onNodeWithText("INLINE-COMPONENT-PANEL").assertExists()
        // The old jump-out cards are replaced by the inline manager.
        compose.onNodeWithText(context.getString(R.string.comp_runtime_components)).assertDoesNotExist()
    }

    @Test fun componentsSubPageKeepsTheEntryCardsWhenNoPanelIsSupplied() {
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalNarrowPane provides false) {
                    SystemPage(state(context, inline = false), actions(), Modifier)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.system_tab_components)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.comp_runtime_components)).assertExists()
    }

    private fun state(context: android.content.Context, inline: Boolean) = FrontEndState(
        installed = null, ready = false, available = null, busy = false, stage = "", percent = -1,
        desktopInstalled = false, offlineAccount = null, offline = false, frameGenLabel = "",
        romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
        controller = ControllerPrefs.read(context),
        componentsPanel = if (inline) ({ Text("INLINE-COMPONENT-PANEL") }) else null,
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
