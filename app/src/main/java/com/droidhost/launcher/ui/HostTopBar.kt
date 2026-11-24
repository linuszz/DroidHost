package com.droidhost.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R

/**
 * The top bar band (design §3.10 · prototype `.topbar`): a 54px glass strip flush with the screen
 * edge, a 1px inner highlight along the top edge and a 1px hairline along the bottom one.
 *
 * The launcher root already reserved the display cutout, so the band slides up by that inset and
 * gives the same pixels back to the page below: the strip covers the punch-hole exactly like the
 * mock, and the first content row starts right at the hairline instead of below a dead band.
 */
@Composable
internal fun TopBarBand(
    barHeight: Dp,
    bleed: Int,
    padH: Dp,
    topPad: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val pal = LocalPalette.current
    val glass = LocalGlassActive.current
    val density = LocalDensity.current
    val bleedDp = with(density) { bleed.toDp() }
    val slotPx = with(density) { (barHeight - bleedDp).coerceAtLeast(10.dp).roundToPx() }
    // A custom layout owns both the band placement and the slot it reserves: the launcher root
    // already consumed the cutout, so the band slides up by that inset and the slot gives the same
    // pixels back to the page below. Modifier.offset cannot do this, because a child taller than
    // its parent is laid out with an extra (child - parent) / 2 offset that Compose applies on top.
    Box(
        modifier = modifier.fillMaxWidth().layout { measurable, constraints ->
            val placeable = measurable.measure(
                Constraints(
                    minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth,
                    minHeight = 0, maxHeight = Constraints.Infinity,
                ),
            )
            layout(constraints.maxWidth, slotPx) { placeable.place(0, -bleed) }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
                .then(
                    if (glass) Modifier.drawBehind {
                        // One tint over the page background (--glass-bg:rgba(22,26,32,.55)); no scrim.
                        // The only specular is the 1px inner highlight along the screen edge.
                        drawRect(pal.glassBg)
                        val hi = 1.dp.toPx()
                        drawRect(pal.glassSpec, topLeft = Offset(0f, 0f), size = Size(size.width, hi))
                        val w = 1.dp.toPx()
                        drawRect(pal.glassLineBrush, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
                    } else Modifier.drawBehind {
                        val w = 1.dp.toPx()
                        drawRect(pal.hairBrush, topLeft = Offset(0f, size.height - w), size = Size(size.width, w))
                    },
                )
                .requiredHeight(barHeight)
                .padding(start = padH, end = padH, top = topPad, bottom = 12.dp),
            content = content,
        )
    }
}

/**
 * The global top bar (design §3.10 · prototype `.topbar`): an edge-to-edge glass strip carrying the
 * wordmark and a muted crumb. It spans the full width with no inset and no corner radius, so the
 * only specular is a 1px inner highlight along its top edge.
 */
@Composable
internal fun HostTopBar(
    crumb: String,
    modifier: Modifier = Modifier,
    padH: androidx.compose.ui.unit.Dp = 18.dp,
    trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    TopBarBand(
        barHeight = if (narrow) 54.dp else 38.dp,
        bleed = topBleedPx(),
        padH = padH,
        topPad = if (narrow) 16.dp else 6.dp,
        modifier = modifier,
    ) {
        Wordmark(12.dp)
        Text(
            "/ " + crumb, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(start = 14.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        trailing()
    }
}
