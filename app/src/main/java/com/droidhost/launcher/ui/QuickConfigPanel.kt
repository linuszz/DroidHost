package com.droidhost.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.launcher.R

// The less-frequent session toggles, shared by the portrait extra-keys gear and the landscape FAB so
// both surfaces stay in lockstep. Labels come from resources and follow the app locale.
data class QuickConfigState(
    val secureKeyboard: Boolean,
    val touchpadActive: Boolean,
    val refreshHz: Int,
    val refreshAuto: Boolean,
    val uiScalePercent: Int,
    val hudOn: Boolean,
    val orientationLabel: String,
    val safeAreaLabel: String,
)

class QuickConfigActions(
    val onToggleSecureKeyboard: () -> Unit,
    val onToggleTouchMode: () -> Unit,
    val onCycleRefreshRate: () -> Unit,
    val onCycleUiScale: () -> Unit,
    val onToggleHud: () -> Unit,
    val onCycleOrientation: () -> Unit,
    val onCycleSafeArea: () -> Unit,
)

/**
 * Transient quick-config popup: a full-screen dismiss scrim plus a 210dp panel of seven rows. The
 * caller positions the panel via [panelModifier] (above the portrait bar, or above the landscape FAB)
 * inside a Box scope. Tapping outside dismisses.
 */
@Composable
fun QuickConfigPanel(
    state: QuickConfigState,
    actions: QuickConfigActions,
    onDismiss: () -> Unit,
    panelModifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss))
    Column(
        panelModifier.width(210.dp)
            .background(Color(0xF2101418), RoundedCornerShape(10.dp))
            .border(1.dp, pal.line2, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ConfigRow(stringResource(R.string.quick_secure_keyboard), if (state.secureKeyboard) stringResource(R.string.touch_on) else stringResource(R.string.touch_off), state.secureKeyboard, actions.onToggleSecureKeyboard)
        ConfigRow(stringResource(R.string.quick_mouse_mode), if (state.touchpadActive) stringResource(R.string.mode_touch_touchpad) else stringResource(R.string.mode_touch_direct), state.touchpadActive, actions.onToggleTouchMode)
        ConfigRow(stringResource(R.string.glasses_refresh), if (state.refreshAuto) "${stringResource(R.string.common_auto)} · ${state.refreshHz} Hz" else "${state.refreshHz} Hz", !state.refreshAuto, actions.onCycleRefreshRate)
        ConfigRow(stringResource(R.string.quick_ui_scale), "${state.uiScalePercent}%", state.uiScalePercent > 100, actions.onCycleUiScale)
        ConfigRow(stringResource(R.string.drawer_hud), if (state.hudOn) stringResource(R.string.touch_on) else stringResource(R.string.touch_off), state.hudOn, actions.onToggleHud)
        ConfigRow(stringResource(R.string.setup_session_orientation), state.orientationLabel, false, actions.onCycleOrientation)
        ConfigRow(stringResource(R.string.safe_area_mode), state.safeAreaLabel, false, actions.onCycleSafeArea)
    }
}

@Composable
private fun ConfigRow(label: String, value: String, active: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(38.dp)
            .background(if (active) pal.signal.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = Color.White, maxLines = 1)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (active) pal.signal else Color.White.copy(alpha = 0.75f), maxLines = 1)
    }
}