package com.droidhost.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w480dp-h800dp-mdpi")
class GlassCapsuleTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun capsuleFloatsWithTenDpInsets() {
        setCapsule(glass = true)
        assertCapsuleInsets()
    }

    @Test fun machinedFallbackKeepsTheSameGeometry() {
        setCapsule(glass = false)
        assertCapsuleInsets()
    }

    @Test fun backdropCopyIsVisualOnly() {
        var realClicks = 0
        var copyClicks = 0
        compose.setContent {
            DroidHostTheme {
                val holder = remember { GlassNavBackdrop() }
                holder.content = { Box(Modifier.fillMaxSize().testTag("nav-go").clickable { copyClicks++ }) }
                holder.scroll = rememberScrollState()
                holder.listSize = Size(480f, 800f)
                CompositionLocalProvider(LocalGlassActive provides true, LocalGlassNav provides holder) {
                    Box(Modifier.size(480.dp, 800.dp).testTag("root")) {
                        GlassCapsule(Modifier.align(Alignment.BottomCenter)) {
                            TextButton(onClick = { realClicks++ }, Modifier.testTag("nav-go")) { Text("Go") }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        // The refracted copy carries no semantics: exactly one interactive navigation node exists.
        assertEquals(1, compose.onAllNodesWithTag("nav-go").fetchSemanticsNodes().size)
        // A tap on the glass away from the real control is swallowed, never forwarded to the copy.
        compose.onNodeWithTag("root").performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(30f, 20f)) }
        compose.runOnIdle { assertEquals(0, copyClicks) }
        compose.onNodeWithTag("nav-go").performClick()
        compose.runOnIdle {
            assertEquals(1, realClicks)
            assertEquals(0, copyClicks)
        }
    }

    private fun setCapsule(glass: Boolean) {
        compose.setContent {
            DroidHostTheme {
                CompositionLocalProvider(LocalGlassActive provides glass) {
                    Box(Modifier.size(480.dp, 800.dp).testTag("root")) {
                        GlassCapsule(Modifier.align(Alignment.BottomCenter)) {
                            TextButton(onClick = {}, Modifier.testTag("nav-go")) { Text("Go") }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
    }

    private fun assertCapsuleInsets() {
        val root = compose.onNodeWithTag("root").getUnclippedBoundsInRoot()
        val capsule = compose.onNodeWithTag("glass-capsule").getUnclippedBoundsInRoot()
        val go = compose.onNodeWithTag("nav-go").getUnclippedBoundsInRoot()
        // The prototype floats the capsule 10px off the screen edges (.navbar{left:10px;right:10px;
        // bottom:10px}) and pads its content by 6px/12px (.navbar{padding:8px 6px 12px}), so the
        // control inside sits 16px from the left edge and 22px above the bottom one.
        assertEquals(root.left + 10.dp, capsule.left)
        assertEquals(root.bottom - 10.dp, capsule.bottom)
        assertEquals(capsule.left + 6.dp, go.left)
        assertEquals(capsule.bottom - 12.dp, go.bottom)
        assertTrue(go.right <= capsule.right - 6.dp)
    }
}
