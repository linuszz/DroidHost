package com.droidhost.launcher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
}

val LocalGlassNav = compositionLocalOf<GlassNavBackdrop?> { null }

private val CapsuleShape = RoundedCornerShape(22.dp)
private val CapsuleBlur = 16.dp

/** The floating portrait navigation capsule: glass over a live backdrop copy, or a machined solid. */
@Composable
internal fun GlassCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val active = LocalGlassActive.current
    val holder = LocalGlassNav.current
    Box(modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .then(if (active) Modifier.glassSurface(CapsuleShape, lens = true) else Modifier.machinedPanel(CapsuleShape))
                .onGloballyPositioned { holder?.navOrigin = it.positionInRoot() },
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
            content()
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun BoxScope.BackdropCopy(holder: GlassNavBackdrop, body: @Composable () -> Unit, scroll: ScrollState) {
    val density = LocalDensity.current
    val blurPx = with(density) { CapsuleBlur.toPx() }
    // Saturation rides the RenderEffect chain: GraphicsLayerScope.colorFilter only exists from compose 1.7.
    val blur = remember(blurPx) {
        val saturate = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.4f) })
        android.graphics.RenderEffect.createColorFilterEffect(
            saturate,
            android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP),
        ).asComposeRenderEffect()
    }
    val width = with(density) { holder.listSize.width.toDp() }
    val height = with(density) { holder.listSize.height.toDp() }
    // The layer is capsule-sized; only the strip behind the glass is blurred, never the whole screen.
    Box(Modifier.matchParentSize().graphicsLayer {
        renderEffect = blur
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }) {
        Box(Modifier.offset {
            IntOffset(
                (holder.listOrigin.x - holder.navOrigin.x).roundToInt(),
                (holder.listOrigin.y - holder.navOrigin.y).roundToInt(),
            )
        }.width(width).height(height)) {
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