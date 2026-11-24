package com.droidhost.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Whether glass materials render; false degrades every floating control to the §3.9 machined solid. */
val LocalGlassActive = compositionLocalOf { false }

/** Bottom clearance a page owes the floating portrait capsule so its last row stays reachable. */
val LocalNavOverlayInset = compositionLocalOf { 0.dp }

/**
 * The scrollable body a page offers the capsule to refract. Only the VM list registers one in v0.4;
 * every other page keeps static glass. The copy shares the page's scroll state and data.
 */
class GlassNavBackdrop {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var scroll by mutableStateOf<ScrollState?>(null)
    var padH by mutableStateOf(0.dp)
    var listOrigin by mutableStateOf(Offset.Zero)
    var listSize by mutableStateOf(Size.Zero)
    var navOrigin by mutableStateOf(Offset.Zero)
    var navHeight by mutableStateOf(0f)
    /** Where the backdrop copy itself was placed; the strip shifts from here onto the page origin. */
    var copyLayout by mutableStateOf(Offset.Zero)
}

val LocalGlassNav = compositionLocalOf<GlassNavBackdrop?> { null }

private val CapsuleShape = RoundedCornerShape(22.dp)
private val CapsuleBlur = 16.dp

/** The floating portrait navigation capsule: glass over a live backdrop copy, or a machined solid. */
@Composable
internal fun GlassCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val active = LocalGlassActive.current
    val holder = LocalGlassNav.current
    val pal = LocalPalette.current
    Box(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .testTag("glass-capsule")
                .clip(CapsuleShape)
                .then(if (active) Modifier else Modifier.machinedPanel(CapsuleShape))
                .onGloballyPositioned {
                    holder?.navOrigin = it.positionInRoot()
                    holder?.navHeight = it.size.height.toFloat()
                },
        ) {
            val body = holder?.content
            val scroll = holder?.scroll
            val live = active && android.os.Build.VERSION.SDK_INT >= 31 && body != null && scroll != null && holder.listSize.width > 0f
            if (live && body != null && scroll != null) {
                BackdropCopy(holder, body, scroll)
                // The copy is visual only; own every pointer event so its duplicated controls never fire.
                Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                })
            }
            // The prototype stacks blur, then rgba(22,26,32,.55) tint, then the controls, so the tint
            // must cover the blurred copy instead of sitting under it.
            if (active) {
                Box(
                    Modifier.matchParentSize().drawBehind {
                        drawRect(pal.glassScrim)
                        drawRect(pal.glassBg)
                        lensWashes(size.width, size.height).forEach { drawRect(it) }
                        // box-shadow: inset 0 1px 0 var(--glass-spec)
                        drawRect(pal.glassSpec, topLeft = Offset(0f, 0f), size = Size(size.width, 1.dp.toPx()))
                        // border: 1px solid var(--glass-line)
                        drawRoundRect(
                            color = pal.glassLine, topLeft = Offset.Zero, size = size,
                            cornerRadius = CornerRadius(22.dp.toPx()), style = Stroke(width = 1.dp.toPx()),
                        )
                    },
                )
            }
            // The prototype's navbar padding (8px 6px 12px) sits inside the glass.
            Box(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 12.dp)) { content() }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun BoxScope.BackdropCopy(holder: GlassNavBackdrop, body: @Composable () -> Unit, scroll: ScrollState) {
    val density = LocalDensity.current
    val width = with(density) { holder.listSize.width.toDp() }
    val height = with(density) { holder.listSize.height.toDp() }
    // The layer is capsule-sized; only the strip behind the glass is blurred, never the whole screen.
    Box(Modifier.matchParentSize().graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }) {
        // The copy is taller than the capsule it lives in, so the box places it with an offset of
        // its own; the displacement is therefore derived from the measured layout position of the
        // copy instead of from the page origin, which keeps the strip aligned whatever the parent
        // does with an oversized child.
        Box(Modifier
            .align(Alignment.TopStart)
            .requiredSize(width, height)
            .onGloballyPositioned { holder.copyLayout = it.positionInRoot() }) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        translationX = holder.listOrigin.x - holder.copyLayout.x
                        translationY = holder.listOrigin.y - holder.copyLayout.y
                        // backdrop-filter: blur(16px) saturate(1.4)
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            val radius = CapsuleBlur.toPx()
                            val blur = android.graphics.RenderEffect.createBlurEffect(
                                radius, radius, android.graphics.Shader.TileMode.CLAMP,
                            )
                            val tint = android.graphics.ColorMatrix().apply { setSaturation(1.4f) }
                            renderEffect = android.graphics.RenderEffect
                                .createColorFilterEffect(android.graphics.ColorMatrixColorFilter(tint), blur)
                                .asComposeRenderEffect()
                        }
                    },
            ) {
                CompositionLocalProvider(LocalFrontFocus provides null) {
                    Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                        androidx.compose.foundation.layout.Column(
                            Modifier.fillMaxSize().verticalScroll(scroll, enabled = false).padding(horizontal = holder.padH),
                        ) { body() }
                    }
                }
            }
        }
    }
}