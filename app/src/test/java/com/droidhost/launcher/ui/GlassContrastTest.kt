package com.droidhost.launcher.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** N1: glass navigation inks must read on any backdrop the scrim can produce (design §3.10). */
class GlassContrastTest {
    @Test fun navigationInksReadOnEveryGlassExtremeInEveryTheme() {
        for (palette in Themes.all) {
            for (ink in listOf(palette.signal, palette.onSurfaceVariant)) {
                val adapted = ThemeColors.inkOn(ink, palette.glassWorst)
                for (backdrop in listOf(Color.Black, Color.White)) {
                    val composite = over(palette.glassBg, over(palette.glassScrim, backdrop))
                    val ratio = contrast(adapted, composite)
                    assertTrue("${palette.id} nav ink on $backdrop: $ratio", ratio >= 4.5f)
                }
            }
        }
    }

    @Test fun runPillLabelsReadOnEveryGlassExtremeInEveryTheme() {
        for (palette in Themes.all) {
            for (tint in listOf(palette.good, palette.onSurfaceVariant)) {
                val adapted = ThemeColors.inkOn(tint, palette.glassWorst)
                for (backdrop in listOf(Color.Black, Color.White)) {
                    val composite = over(palette.glassBg, over(palette.glassScrim, backdrop))
                    val ratio = contrast(adapted, composite)
                    assertTrue("${palette.id} run pill on $backdrop: $ratio", ratio >= 4.5f)
                }
            }
        }
    }

    @Test fun glassTokensMatchTheSpec() {
        // sRGB colors quantize alpha to 8 bits; compare within half a quantization step.
        for (palette in Themes.all) {
            if (palette.background.luminance() > 0.5f) {
                assertTrue("${palette.id} light glass alpha", kotlin.math.abs(palette.glassBg.alpha - 0.78f) < 0.004f)
                assertTrue("${palette.id} light scrim off", palette.glassScrim.alpha == 0f)
            } else {
                assertTrue("${palette.id} dark glass alpha", kotlin.math.abs(palette.glassBg.alpha - 0.55f) < 0.004f)
                assertTrue("${palette.id} dark scrim", kotlin.math.abs(palette.glassScrim.alpha - 0.55f) < 0.004f)
            }
        }
    }

    private fun over(src: Color, dst: Color): Color = Color(
        src.red * src.alpha + dst.red * (1f - src.alpha),
        src.green * src.alpha + dst.green * (1f - src.alpha),
        src.blue * src.alpha + dst.blue * (1f - src.alpha),
    )

    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + 0.05f) / (dark + 0.05f)
    }
}
