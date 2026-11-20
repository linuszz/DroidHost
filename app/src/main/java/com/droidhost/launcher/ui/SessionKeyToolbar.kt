package com.droidhost.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Keyboard
import com.droidhost.launcher.input.PC_MODIFIERS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MOD_OFF = 0
private const val MOD_ONCE = 1
private const val MOD_LOCKED = 2
private const val DOUBLE_TAP_MS = 300L

private data class ToolbarKey(val label: String, val code: Int)

// Scrollable typing/modifier keys; the arrows and PageUp/PageDown live in the fixed cluster instead.
private val TOOLBAR_ROW_TOP = listOf(
    ToolbarKey("Esc", 1), ToolbarKey("Tab", 15), ToolbarKey("/", 53),
    ToolbarKey("Home", 102), ToolbarKey("End", 107), ToolbarKey("Del", 111),
)
private val TOOLBAR_ROW_BOTTOM = listOf(
    ToolbarKey("Shift", 42), ToolbarKey("Ctrl", 29), ToolbarKey("Alt", 56), ToolbarKey("Super", 125),
)

// ThinkPad-style navigation cluster: PageUp/PageDn above the outer arrows, inverted-T below.
private val ARROW_CLUSTER_TOP = listOf(
    ToolbarKey("PgUp", 104), ToolbarKey("↑", 103), ToolbarKey("PgDn", 109),
)
private val ARROW_CLUSTER_BOTTOM = listOf(
    ToolbarKey("←", 105), ToolbarKey("↓", 108), ToolbarKey("→", 106),
)

/**
 * A compact Haven-style extra-keys bar shown above the soft keyboard (or the navigation bar when the
 * keyboard is hidden) in portrait. Modifiers: tap = hold for one key (auto-released after it, or by
 * tapping the same key again); double-tap = lock; tap a locked key = release. The left typing keys
 * scroll; the right cluster stays fixed: a keyboard toggle and a quick-config gear (which pops open a
 * panel of the less-frequent toggles) beside the ThinkPad arrows. It reports its measured height so the
 * session can reserve it out of the guest output area.
 */
@Composable
fun SessionKeyToolbar(
    bottomOffsetPx: Int,
    sendKey: (Int, Boolean) -> Unit,
    onToggleIme: () -> Unit,
    imeOpen: Boolean,
    secureKeyboard: Boolean,
    onToggleSecureKeyboard: () -> Unit,
    onPaste: () -> Unit,
    touchpadActive: Boolean,
    onToggleTouchMode: () -> Unit,
    refreshHz: Int,
    refreshAuto: Boolean,
    onCycleRefreshRate: () -> Unit,
    uiScalePercent: Int,
    onCycleUiScale: () -> Unit,
    hudOn: Boolean,
    onToggleHud: () -> Unit,
    onHeightChanged: (Int) -> Unit,
) {
    val pal = LocalPalette.current
    val mods = remember { mutableStateMapOf<Int, Int>() }
    val lastModTap = remember { mutableStateOf(0L) }
    val lastModKey = remember { mutableStateOf(0) }
    var configOpen by remember { mutableStateOf(false) }
    var barHeightPx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var superTrigger by remember { mutableStateOf<Job?>(null) }
    val bottomOffset = with(LocalDensity.current) { bottomOffsetPx.toDp() }

    fun releaseOnceModifiers() {
        for ((code, state) in mods.toMap()) if (state == MOD_ONCE) { sendKey(code, false); mods[code] = MOD_OFF }
    }
    fun press(key: ToolbarKey) {
        // Super is a trigger, not a hold-for-one-key modifier: a single tap fires Super down+up (opens
        // the desktop menu) once the double-tap window passes; a double tap locks it down for combos.
        if (key.code == 125) {
            val now = SystemClock.uptimeMillis()
            val doubleTap = lastModKey.value == 125 && now - lastModTap.value <= DOUBLE_TAP_MS
            superTrigger?.cancel(); superTrigger = null
            when {
                (mods[125] ?: MOD_OFF) == MOD_LOCKED -> { sendKey(125, false); mods[125] = MOD_OFF }
                doubleTap -> { sendKey(125, true); mods[125] = MOD_LOCKED }
                else -> superTrigger = scope.launch {
                    delay(DOUBLE_TAP_MS)
                    sendKey(125, true); sendKey(125, false)
                }
            }
            lastModTap.value = now
            lastModKey.value = 125
            return
        }
        if (key.code in PC_MODIFIERS) {
            val now = SystemClock.uptimeMillis()
            val doubleTap = lastModKey.value == key.code && now - lastModTap.value <= DOUBLE_TAP_MS
            when {
                (mods[key.code] ?: MOD_OFF) == MOD_LOCKED -> { sendKey(key.code, false); mods[key.code] = MOD_OFF }
                doubleTap -> { if ((mods[key.code] ?: MOD_OFF) != MOD_ONCE) sendKey(key.code, true); mods[key.code] = MOD_LOCKED }
                (mods[key.code] ?: MOD_OFF) == MOD_ONCE -> { sendKey(key.code, false); mods[key.code] = MOD_OFF }
                else -> { sendKey(key.code, true); mods[key.code] = MOD_ONCE }
            }
            lastModTap.value = now
            lastModKey.value = key.code
            return
        }
        sendKey(key.code, true)
    }
    fun release(key: ToolbarKey) {
        if (key.code in PC_MODIFIERS) return
        sendKey(key.code, false)
        releaseOnceModifiers()
    }
    DisposableEffect(Unit) {
        onDispose {
            for ((code, state) in mods.toMap()) if (state != MOD_OFF) sendKey(code, false)
            onHeightChanged(0)
        }
    }

    Box(Modifier.fillMaxSize().padding(bottom = bottomOffset), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { barHeightPx = it.size.height; onHeightChanged(it.size.height) }
                .background(Color(0xE6101418), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .border(1.dp, pal.line2, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); waitForUpOrCancellation()?.consume() } }
                .padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TOOLBAR_ROW_TOP.forEach { key ->
                            val state = mods[key.code] ?: MOD_OFF
                            KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { press(key) }, { release(key) })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TOOLBAR_ROW_BOTTOM.forEach { key ->
                            val state = mods[key.code] ?: MOD_OFF
                            KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { press(key) }, { release(key) })
                        }
                        ActionCap("Paste", onPaste)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        IconCap(Icons.Outlined.Keyboard, "Toggle keyboard", onToggleIme, active = imeOpen, width = 38.dp, height = 40.dp)
                        IconCap(Icons.Filled.Settings, "Quick config", { configOpen = !configOpen }, active = configOpen, width = 38.dp, height = 40.dp)
                    }
                    Box(Modifier.width(1.dp).height(76.dp).background(pal.line2))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ARROW_CLUSTER_TOP.forEach { key ->
                                val state = mods[key.code] ?: MOD_OFF
                                KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { press(key) }, { release(key) }, 38.dp, 40.dp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ARROW_CLUSTER_BOTTOM.forEach { key ->
                                val state = mods[key.code] ?: MOD_OFF
                                KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { press(key) }, { release(key) }, 38.dp, 40.dp)
                            }
                        }
                    }
                }
            }
        }
        if (configOpen) {
            Box(Modifier.fillMaxSize().clickable { configOpen = false })
            val barH = with(LocalDensity.current) { barHeightPx.toDp() }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(bottom = barH + 6.dp, end = 6.dp)
                    .width(210.dp)
                    .background(Color(0xF2101418), RoundedCornerShape(10.dp))
                    .border(1.dp, pal.line2, RoundedCornerShape(10.dp))
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ConfigRow("Secure keyboard", if (secureKeyboard) "On" else "Off", secureKeyboard, onToggleSecureKeyboard)
                ConfigRow("Mouse mode", if (touchpadActive) "Touchpad" else "Direct", touchpadActive, onToggleTouchMode)
                ConfigRow("Refresh rate", if (refreshAuto) "Auto · $refreshHz Hz" else "$refreshHz Hz", !refreshAuto, onCycleRefreshRate)
                ConfigRow("UI scale", "$uiScalePercent%", uiScalePercent > 100, onCycleUiScale)
                ConfigRow("Performance HUD", if (hudOn) "On" else "Off", hudOn, onToggleHud)
            }
        }
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

@Composable
private fun KeyCap(
    label: String,
    lit: Boolean,
    locked: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    width: Dp = 46.dp,
    height: Dp = 40.dp,
) {
    val pal = LocalPalette.current
    var down by remember { mutableStateOf(false) }
    val fill by animateColorAsState(
        when {
            down -> pal.signal.copy(alpha = 0.55f)
            lit -> pal.signal.copy(alpha = if (locked) 0.45f else 0.28f)
            else -> Color.White.copy(alpha = 0.07f)
        }, Motion.tw(160), label = "toolbarKeyFill",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(width).height(height)
            .background(fill, RoundedCornerShape(7.dp))
            .border(1.dp, if (lit) pal.signal.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(7.dp))
            .pointerInput(Unit) {
                // Fire on a confirmed tap, not on touch-down: the rows scroll horizontally, and a
                // drag that starts on a key (Ctrl/Alt) must scroll, not press it.
                detectTapGestures(
                    onPress = { down = true; tryAwaitRelease(); down = false },
                    onTap = { onDown(); onUp() },
                )
            },
    ) {
        Text(label, fontSize = if (label.length > 2) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun ActionCap(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(46.dp).height(40.dp)
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
    ) {
        Text(label, fontSize = if (label.length > 2) 11.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun IconCap(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    active: Boolean = false,
    width: Dp = 46.dp,
    height: Dp = 40.dp,
) {
    val pal = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(width).height(height)
            .background(if (active) pal.signal.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
            .border(1.dp, if (active) pal.signal.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = desc, tint = if (active) pal.signal else Color.White, modifier = Modifier.size(20.dp))
    }
}