package com.droidhost.launcher.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

@Composable
internal fun LogoThrobber(modifier: Modifier = Modifier, running: Boolean = true) {
    val ink = MaterialTheme.colorScheme.onBackground
    val ball = LocalPalette.current.signal
    val loop = rememberInfiniteTransition(label = "throbber")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "loop")
    val still = !running || Motion.scale == 0f
    val frame = remember { Path() }
    Canvas(modifier.aspectRatio(LogoShape.WIDTH / LogoShape.HEIGHT)) {
        val p = if (still) 0f else loop.value
        val turn = if (p < 0.16f || p > 0.84f) 0f else Turn.transform((p - 0.16f) / 0.68f) * 360f
        val coreScale = when {
            p < 0.12f -> 1f
            p < 0.30f -> 1f - 0.2f * Settle.transform((p - 0.12f) / 0.18f)
            p < 0.70f -> 0.8f
            p < 0.88f -> 0.8f + 0.2f * Settle.transform((p - 0.70f) / 0.18f)
            else -> 1f
        }
        val s = size.width / LogoShape.WIDTH
        fun px(x: Float, y: Float) = Offset((x - LogoShape.LEFT) * s, y * s)
        val centre = px(LogoShape.CX, LogoShape.CY)
        LogoShape.frame(frame, ::px)
        rotate(turn, centre) {
            drawPath(frame, ink, style = Stroke(LogoShape.STROKE * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val half = LogoShape.CORE * s * coreScale
        drawRoundRect(ball, Offset(centre.x - half, centre.y - half), Size(2 * half, 2 * half), CornerRadius(half * 0.5f))
    }
}

internal fun throbberBall(bounds: androidx.compose.ui.geometry.Rect): Pair<Offset, Float> {
    val s = bounds.width / LogoShape.WIDTH
    return Offset(bounds.left + (LogoShape.CX - LogoShape.LEFT) * s, bounds.top + LogoShape.CY * s) to LogoShape.CORE * s
}

private const val LOOP_MS = 1900
private val Settle = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
private val Turn = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

internal object LogoShape {
    const val LEFT = 0f
    const val WIDTH = 240f
    const val HEIGHT = 240f
    const val CX = 120f
    const val CY = 120f
    const val CORE = 46f
    const val STROKE = 22f

    fun frame(path: Path, px: (Float, Float) -> Offset) {
        path.reset()
        fun m(x: Float, y: Float) { val o = px(x, y); path.moveTo(o.x, o.y) }
        fun l(x: Float, y: Float) { val o = px(x, y); path.lineTo(o.x, o.y) }
        fun q(cx: Float, cy: Float, x: Float, y: Float) { val c = px(cx, cy); val o = px(x, y); path.quadraticBezierTo(c.x, c.y, o.x, o.y) }
        m(209f, 137f); l(209f, 180f); q(209f, 209f, 180f, 209f); l(60f, 209f); q(31f, 209f, 31f, 180f)
        l(31f, 60f); q(31f, 31f, 60f, 31f); l(180f, 31f); q(209f, 31f, 209f, 60f); l(209f, 103f)
    }

    fun core(path: Path, px: (Float, Float) -> Offset) {
        path.reset()
        fun m(x: Float, y: Float) { val o = px(x, y); path.moveTo(o.x, o.y) }
        fun l(x: Float, y: Float) { val o = px(x, y); path.lineTo(o.x, o.y) }
        fun q(cx: Float, cy: Float, x: Float, y: Float) { val c = px(cx, cy); val o = px(x, y); path.quadraticBezierTo(c.x, c.y, o.x, o.y) }
        m(97f, 74f); l(143f, 74f); q(166f, 74f, 166f, 97f); l(166f, 143f); q(166f, 166f, 143f, 166f)
        l(97f, 166f); q(74f, 166f, 74f, 143f); l(74f, 97f); q(74f, 74f, 97f, 74f); path.close()
    }
}