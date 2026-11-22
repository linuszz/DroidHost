package com.droidhost.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droidhost.launcher.R

/**
 * Landscape desktop quick-config entry: a ~40dp gear FAB pinned to the bottom-end inside the safe area
 * (cutout/corner inset), opening the same [QuickConfigPanel] as the portrait toolbar gear. Zero resident
 * height cost — landscape height is scarce, width is plentiful. The caller gates visibility (landscape
 * desktop, no drawer/PcKeyboard/PiP/loading) so this composable only renders when wanted.
 */
@Composable
fun LandscapeControlsFab(
    config: QuickConfigState,
    actions: QuickConfigActions,
    safeInsetRightPx: Int,
    safeInsetBottomPx: Int,
) {
    val pal = LocalPalette.current
    var configOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val endPad = with(density) { safeInsetRightPx.toDp() } + 8.dp
    val bottomPad = with(density) { safeInsetBottomPx.toDp() } + 8.dp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = endPad, bottom = bottomPad)
                .size(40.dp)
                .background(Color(0xE6101418), CircleShape)
                .border(1.dp, if (configOpen) pal.signal.copy(alpha = 0.7f) else pal.line2, CircleShape)
                .clickable { configOpen = !configOpen },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.session_quick_config),
                tint = if (configOpen) pal.signal else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        if (configOpen) {
            QuickConfigPanel(config, actions, { configOpen = false },
                Modifier.align(Alignment.BottomEnd).padding(end = endPad, bottom = bottomPad + 46.dp))
        }
    }
}