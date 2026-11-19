package com.droidhost.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droidhost.launcher.runtime.FlatpakManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class FlatpakAppStripTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val opened = mutableListOf<Pair<String, String>>()
    private var stores = 0

    private fun app(id: String, name: String) = FlatpakManager.App(id, name, null, "stable", null)

    private fun show(apps: List<FlatpakManager.App>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost {
                        Box(Modifier.fillMaxSize()) {
                            FlatpakAppStrip(apps, onOpen = { id, name -> opened.add(id to name) }, onStore = { stores++ })
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    @Test fun noInstalledAppsRenderNothing() {
        show(emptyList())
        compose.onNodeWithText("Flathub").assertDoesNotExist()
    }

    @Test fun installedAppsAreListedAndOneTapOpensThem() {
        show(listOf(app("org.mozilla.firefox", "Firefox"), app("org.gimp.GIMP", "GIMP")))
        compose.onNodeWithText("Firefox").assertIsDisplayed()
        compose.onNodeWithText("GIMP").assertIsDisplayed()
        compose.onNodeWithText("Firefox").performClick()
        assertEquals(listOf("org.mozilla.firefox" to "Firefox"), opened)
    }

    @Test fun theStoreTileOpensFlathub() {
        show(listOf(app("org.mozilla.firefox", "Firefox")))
        compose.onNodeWithText("Flathub").performClick()
        assertEquals(1, stores)
    }
}