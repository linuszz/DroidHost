package com.droidhost.launcher.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.material3.darkColorScheme
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.droidhost.launcher.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.droidhost.launcher.core.AppUiPrefs
import com.droidhost.launcher.session.SessionPrefs

class Palette(
    val id: String, @StringRes val label: Int, @StringRes val detail: Int,
    val background: Color, val surface: Color, val surfaceVariant: Color, val line: Color, val line2: Color,
    val onBackground: Color, val onSurfaceVariant: Color,
    val primary: Color, val primary2: Color, val onPrimary: Color, val signal: Color,
    val good: Color, val error: Color = Color(0xFFFF8A80),
    warn: Color = Color(0xFFFFB74D),
) {
    val warn: Color = ThemeColors.semantic(warn.toArgb(), background)
}

object Themes {
    const val GRAPHITE = "graphite"
    const val PAPER = "paper"
    const val ICON_BLUE = "blue"
    const val ELECTRIC = "electric"
    const val INNER_GAMER = "inner_gamer"
    const val GRADIENT = "gradient"

    const val DEFAULT_PRIMARY = 0xFF8B5CF6.toInt()
    const val DEFAULT_SECONDARY = 0xFF22D3EE.toInt()

    var gradientColors by mutableStateOf(DEFAULT_PRIMARY to DEFAULT_SECONDARY)
        private set

    const val RGB_SPEEDS = 10
    const val DEFAULT_RGB_SPEED = 3
    const val RGB_HUE_STEP = 3f
    const val WHITE_RED = "white_red"

    var rgbSpeed by mutableStateOf(DEFAULT_RGB_SPEED)
        private set

    var rgbHue by mutableStateOf(0f)
        private set

    fun moveRgbHue(hue: Float) {
        rgbHue = hue
    }

    fun setRgbSpeed(context: Context, speed: Int) {
        val value = speed.coerceIn(1, RGB_SPEEDS)
        SessionPrefs.setRgbSpeed(context, value)
        rgbSpeed = value
    }

    fun rgbCycleMillis(speed: Int): Int = 30_000 / speed.coerceIn(1, RGB_SPEEDS)

    fun loadGradient(context: Context) {
        gradientColors = SessionPrefs.gradientColors(context)
        rgbSpeed = SessionPrefs.rgbSpeed(context)
    }

    fun innerGamer(hue: Float): Palette = ThemeColors.palette(
        Color.hsv(hue % 360f, 0.9f, 1f).toArgb(), Color.hsv((hue + 60f) % 360f, 0.9f, 1f).toArgb(),
        R.string.theme_inner_gamer, R.string.theme_inner_gamer_detail, INNER_GAMER,
    )

    fun setGradient(context: Context, primary: Int, secondary: Int) {
        SessionPrefs.setGradientColors(context, primary, secondary)
        gradientColors = primary to secondary
    }

    fun current(context: Context): Palette {
        val id = SessionPrefs.theme(context)
        if (id == GRADIENT || id == INNER_GAMER) loadGradient(context)
        return byId(id)
    }

    val all: List<Palette> = listOf(
        Palette(
            GRAPHITE, R.string.theme_graphite, R.string.theme_graphite_detail,
            background = Color(0xFF0A0B0D), surface = Color(0xFF121417), surfaceVariant = Color(0xFF1A1D22), line = Color(0xFF262A31), line2 = Color(0xFF343A43),
            onBackground = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF9AA3AF),
            primary = Color(0xFF00E5A0), primary2 = Color(0xFF00C48C), onPrimary = Color(0xFF031A12), signal = Color(0xFF00E5A0),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            PAPER, R.string.theme_paper, R.string.theme_paper_detail,
            background = Color(0xFF000000), surface = Color(0xFF0F0F10), surfaceVariant = Color(0xFF191A1C), line = Color(0xFF262729), line2 = Color(0xFF343638),
            onBackground = Color(0xFFF9F9F9), onSurfaceVariant = Color(0xFF8E9196),
            primary = Color(0xFFF9F9F9), primary2 = Color(0xFFD9DADD), onPrimary = Color(0xFF000000), signal = Color(0xFFF9F9F9),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            ICON_BLUE, R.string.theme_icon_blue, R.string.theme_icon_blue_detail,
            background = Color(0xFF050608), surface = Color(0xFF0E1116), surfaceVariant = Color(0xFF161B23), line = Color(0xFF1F2630), line2 = Color(0xFF2A3340),
            onBackground = Color(0xFFF2F4F7), onSurfaceVariant = Color(0xFF8A93A0),
            primary = Color(0xFF136CE0), primary2 = Color(0xFF0B4FB0), onPrimary = Color(0xFFFFFFFF), signal = Color(0xFF136CE0),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            ELECTRIC, R.string.theme_electric, R.string.theme_electric_detail,
            background = Color(0xFF070A10), surface = Color(0xFF0F1522), surfaceVariant = Color(0xFF16203A), line = Color(0xFF1E2A47), line2 = Color(0xFF2A3A5E),
            onBackground = Color(0xFFEEF3FF), onSurfaceVariant = Color(0xFF8B9AB8),
            primary = Color(0xFF2E86FF), primary2 = Color(0xFF2684FF), onPrimary = Color(0xFF03111F), signal = Color(0xFF2E86FF),
            good = Color(0xFF4CD37F),
        ),
        Palette(
            WHITE_RED, R.string.theme_white_red, R.string.theme_white_red_detail,
            background = Color(0xFFF8F8F8), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFF0F0F0), line = Color(0xFFDDDDDD), line2 = Color(0xFFC8C8C8),
            onBackground = Color(0xFF191919), onSurfaceVariant = Color(0xFF555555),
            primary = Color(0xFFD62828), primary2 = Color(0xFFB71C1C), onPrimary = Color(0xFFFFFFFF), signal = Color(0xFFD62828),
            good = Color(0xFF248548),
        ),
        innerGamer(0f),
        ThemeColors.palette(DEFAULT_PRIMARY, DEFAULT_SECONDARY, R.string.theme_gradient, R.string.theme_gradient_detail),
    )

    fun byId(id: String): Palette {
        if (id == INNER_GAMER) return innerGamer(rgbHue)
        if (id == GRADIENT) return ThemeColors.palette(gradientColors.first, gradientColors.second, R.string.theme_gradient, R.string.theme_gradient_detail)
        return all.firstOrNull { it.id == id } ?: all[0]
    }
}

val LocalPalette = staticCompositionLocalOf { Themes.byId(Themes.GRAPHITE) }

val Palette.onSignal: Color get() = if (signal.luminance() > 0.18f) Color(0xFF03111F) else Color.White

// Machined texture layer (design §3.9): neutral light/shadow derived per palette, never fixed values.
private val Palette.isLight: Boolean get() = background.luminance() > 0.5f

val Palette.edgeHi: Color get() = (if (isLight) Color.Black else Color.White).copy(alpha = 0.05f)
val Palette.edgeLo: Color get() = Color.Black.copy(alpha = if (isLight) 0.12f else 0.45f)
val Palette.cardTop: Color get() = lerp(surface, Color.White, 0.04f)
val Palette.cardBot: Color get() = lerp(surface, Color.Black, 0.04f)
val Palette.hair: Color get() = (if (isLight) Color.Black else Color.White).copy(alpha = 0.06f)
val Palette.glow: Color get() = primary.copy(alpha = 0.16f)

val Palette.hairBrush: Brush get() = Brush.horizontalGradient(
    0f to Color.Transparent, 0.12f to hair, 0.88f to hair, 1f to Color.Transparent,
)

// Liquid Glass layer (design §3.10): floating controls only; information surfaces stay machined.
// The prototype tints glass with a dark surface at 55% (--glass-bg:rgba(22,26,32,.55)); a light
// tint would wash the bar out to grey, so the dark branch stays close to the background value.
val Palette.glassBg: Color get() =
    if (isLight) lerp(surfaceVariant, Color.White, 0.5f).copy(alpha = 0.78f)
    else lerp(surfaceVariant, background, 0.2f).copy(alpha = 0.55f)
// The backdrop behind a floating control is uncontrollable; this scrim caps its worst-case luminance.
val Palette.glassScrim: Color get() = if (isLight) Color.Transparent else background.copy(alpha = 0.55f)
val Palette.glassLine: Color get() = (if (isLight) Color.Black else Color.White).copy(alpha = 0.14f)
val Palette.glassSpec: Color get() = (if (isLight) Color.Black else Color.White).copy(alpha = if (isLight) 0.10f else 0.22f)

val Palette.glassLineBrush: Brush get() = Brush.horizontalGradient(
    0f to Color.Transparent, 0.12f to glassLine, 0.88f to glassLine, 1f to Color.Transparent,
)

private fun glassOver(src: Color, dst: Color): Color = Color(
    src.red * src.alpha + dst.red * (1f - src.alpha),
    src.green * src.alpha + dst.green * (1f - src.alpha),
    src.blue * src.alpha + dst.blue * (1f - src.alpha),
)

/** The glass stack composited over the brightest (dark themes) or darkest (light themes) backdrop. */
val Palette.glassWorst: Color get() = glassOver(glassBg, glassOver(glassScrim, if (isLight) Color.Black else Color.White))

/** Top inner highlight and bottom inner shadow, drawn inside the node's clip; inset clears a border. */
@Composable
internal fun Modifier.machinedBevel(inset: Dp = 0.dp): Modifier {
    val pal = LocalPalette.current
    val hi = pal.edgeHi
    val lo = pal.edgeLo
    return drawBehind {
        val w = 1.dp.toPx()
        val pad = inset.toPx()
        drawRect(hi, topLeft = Offset(0f, pad), size = Size(size.width, w))
        drawRect(lo, topLeft = Offset(0f, size.height - pad - w), size = Size(size.width, w))
    }
}

/** Static screen texture: a faint primary halo up top, a black floor below, a 22dp dot grid. */
@Composable
internal fun Modifier.screenTexture(): Modifier {
    val pal = LocalPalette.current
    val halo = pal.primary.copy(alpha = 0.045f)
    val floor = Color.Black.copy(alpha = if (pal.isLight) 0.08f else 0.55f)
    val dot = (if (pal.isLight) Color.Black else Color.White).copy(alpha = 0.018f)
    return drawWithCache {
        val step = 22.dp.toPx()
        val points = ArrayList<Offset>()
        var y = step / 2f
        while (y < size.height) {
            var x = step / 2f
            while (x < size.width) { points.add(Offset(x, y)); x += step }
            y += step
        }
        onDrawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(halo, Color.Transparent),
                    center = Offset(size.width / 2f, -size.height * 0.08f), radius = size.height * 0.62f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(floor, Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 1.08f), radius = size.height * 0.45f,
                ),
            )
            drawPoints(points, PointMode.Points, dot, strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * The prototype's lens washes are CSS ellipses, not circles:
 * `radial-gradient(120% 60% at 50% -20%, rgba(255,255,255,.16), transparent 55%)`.
 * A circle of the same radius bleeds the wash across the whole control and turns the dark
 * capsule into a grey slab, so the circle is squashed into the prototype's ellipse through the
 * shader's local matrix.
 */
private fun lensBrush(
    w: Float, h: Float,
    cx: Float, cy: Float, rx: Float, ry: Float,
    alpha: Float, stop: Float,
): Brush {
    val r = rx
    // The local matrix maps shader space to device space, so the vertical scale must squash the
    // circle down to ry (ry/r); r/ry stretches it instead and bleeds the wash over the whole control.
    val sy = if (r > 0f) ry / r else 1f
    val shader = android.graphics.RadialGradient(
        cx, cy, r,
        intArrayOf(Color.White.copy(alpha = alpha).toArgb(), Color.Transparent.toArgb()),
        floatArrayOf(0f, stop),
        android.graphics.Shader.TileMode.CLAMP,
    )
    shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, sy, cx, cy) })
    return ShaderBrush(shader)
}

/** Whether the launcher draws edge to edge; decides which top inset the root reserves. */
val LocalLauncherFullscreen = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Pixels the window paints above the app content: the punch-hole cutout (or the status bar)
 * that the launcher reserves in fullscreen mode. The top bar's glass bleeds over them so the
 * strip reaches the screen edge like the prototype's `.topbar`.
 */
@Composable
internal fun topBleedPx(): Int {
    val view = androidx.compose.ui.platform.LocalView.current
    // WindowInsets.getInsets(int) only exists from API 30 and the launcher still runs on API 26,
    // so the compat wrapper resolves the insets on every release. The cutout inset has to come
    // from the same call the root uses (WindowInsets.displayCutout), otherwise the band slides
    // up by a different amount than the space the root reserved.
    val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view) ?: return 0
    return if (LocalLauncherFullscreen.current) {
        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout()).top
    } else {
        insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
    }
}

/** The prototype's lens stack: a top wash plus the two corner highlights (design §3.10). */
internal fun lensWashes(w: Float, h: Float): List<Brush> = listOf(
    lensBrush(w, h, w / 2f, -h * 0.2f, w * 1.2f, h * 0.6f, 0.16f, 0.55f),
    lensBrush(w, h, 0f, 0f, w * 0.6f, h * 0.4f, 0.09f, 0.60f),
    lensBrush(w, h, w, 0f, w * 0.6f, h * 0.4f, 0.09f, 0.60f),
)

/** Static glass for in-flow floating controls: tint, specular and (optionally) a lens edge. No live blur. */
@Composable
internal fun Modifier.glassSurface(shape: Shape = RectangleShape, lens: Boolean = false, edgeFade: Boolean = false): Modifier {
    val pal = LocalPalette.current
    val scrim = pal.glassScrim
    val bg = pal.glassBg
    val line = pal.glassLine
    val lineBrush = pal.glassLineBrush
    val base = clip(shape).drawWithCache {
        val w = size.width
        val h = size.height
        val washes = if (lens) lensWashes(w, h) else emptyList()
        onDrawBehind {
            drawRect(scrim)
            drawRect(bg)
            washes.forEach { drawRect(it) }
            // The portrait capsule carries a crisp 1px inner highlight along its top edge
            // (box-shadow: inset 0 1px 0 var(--glass-spec)).
            if (lens) drawRect(pal.glassSpec, topLeft = Offset(0f, 0f), size = Size(w, 1.dp.toPx()))
            if (edgeFade) drawRect(lineBrush, topLeft = Offset(0f, h - 1.dp.toPx()), size = Size(w, 1.dp.toPx()))
        }
    }
    return if (edgeFade) base else base.border(1.dp, line, shape)
}

/** Solid fallback for glass controls: the §3.9 neutral machined layer, visually distinct from glass. */
@Composable
internal fun Modifier.machinedPanel(shape: Shape): Modifier {
    val pal = LocalPalette.current
    return clip(shape)
        .background(Brush.verticalGradient(listOf(pal.cardTop, pal.cardBot)))
        .machinedBevel(inset = 1.dp)
        .border(1.dp, pal.line2, shape)
}

@Composable
fun DroidHostTheme(
    theme: String? = null,
    appScale: Int = AppUiPrefs.DEFAULT_SCALE,
    fontScale: Int = SessionPrefs.FONT_SCALE_DEFAULT,
    content: @Composable () -> Unit,
) {
    val id = theme ?: SessionPrefs.theme(LocalContext.current)
    if (id == Themes.INNER_GAMER) InnerGamerHue(Themes.rgbSpeed)
    val p = Themes.byId(id)
    val scheme = darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, secondary = p.onSurfaceVariant,
        background = p.background, onBackground = p.onBackground,
        surface = p.surface, onSurface = p.onBackground,
        surfaceVariant = p.surfaceVariant, onSurfaceVariant = p.onSurfaceVariant,
        error = p.error,
    )
    val baseDensity = LocalDensity.current
    val baseConfiguration = LocalConfiguration.current
    val fontPercent = fontScale
    val density = remember(baseDensity, appScale, fontPercent) { scaledAppDensity(baseDensity, appScale, fontPercent) }
    val configuration = remember(baseConfiguration, appScale) { scaledAppConfiguration(baseConfiguration, appScale) }
    CompositionLocalProvider(
        LocalPalette provides p,
        LocalDensity provides density,
        LocalConfiguration provides configuration,
    ) {
        MaterialTheme(colorScheme = scheme, typography = ContentDirected) {
            FocusGlideHost { ProvideTextStyle(TextStyle(textDirection = TextDirection.Content), content) }
        }
    }
}

private val ContentDirected = Typography().run {
    fun TextStyle.byContent() = copy(textDirection = TextDirection.Content)
    Typography(
        displayLarge.byContent(), displayMedium.byContent(), displaySmall.byContent(),
        headlineLarge.byContent(), headlineMedium.byContent(), headlineSmall.byContent(),
        titleLarge.byContent(), titleMedium.byContent(), titleSmall.byContent(),
        bodyLarge.byContent(), bodyMedium.byContent(), bodySmall.byContent(),
        labelLarge.byContent(), labelMedium.byContent(), labelSmall.byContent(),
    )
}

@Composable
private fun InnerGamerHue(speed: Int) {
    if (Motion.scale == 0f) return
    key(speed) { InnerGamerCycle(speed) }
}

@Composable
private fun InnerGamerCycle(speed: Int) {
    val start = remember { Themes.rgbHue }
    val hue by rememberInfiniteTransition(label = "innerGamer").animateFloat(
        start, start + 360f, infiniteRepeatable(tween(Themes.rgbCycleMillis(speed).coerceAtLeast(1), easing = LinearEasing), RepeatMode.Restart), label = "hue",
    )
    val stepped = ((hue % 360f) / Themes.RGB_HUE_STEP).toInt() * Themes.RGB_HUE_STEP
    SideEffect { Themes.moveRgbHue(stepped) }
}
