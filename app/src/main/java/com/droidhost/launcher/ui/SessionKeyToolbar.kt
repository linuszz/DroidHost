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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Close
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

// Modifier hold/lock state shared by the portrait toolbar and the landscape vertical bar so both
// behave identically: tap = hold for one key, double-tap = lock, tap a locked key = release.
private class ModifierKeys(private val sendKey: (Int, Boolean) -> Unit, private val scope: kotlinx.coroutines.CoroutineScope) {
    val mods = mutableStateMapOf<Int, Int>()
    private var lastModTap = 0L
    private var lastModKey = 0
    private var superTrigger: Job? = null

    private fun releaseOnceModifiers() {
        for ((code, state) in mods.toMap()) if (state == MOD_ONCE) { sendKey(code, false); mods[code] = MOD_OFF }
    }
    fun press(key: ToolbarKey) {
        // Super is a trigger, not a hold-for-one-key modifier: a single tap fires Super down+up (opens
        // the desktop menu) once the double-tap window passes; a double tap locks it down for combos.
        if (key.code == 125) {
            val now = SystemClock.uptimeMillis()
            val doubleTap = lastModKey == 125 && now - lastModTap <= DOUBLE_TAP_MS
            superTrigger?.cancel(); superTrigger = null
            when {
                (mods[125] ?: MOD_OFF) == MOD_LOCKED -> { sendKey(125, false); mods[125] = MOD_OFF }
                doubleTap -> { sendKey(125, true); mods[125] = MOD_LOCKED }
                else -> superTrigger = scope.launch {
                    delay(DOUBLE_TAP_MS)
                    sendKey(125, true); sendKey(125, false)
                }
            }
            lastModTap = now
            lastModKey = 125
            return
        }
        if (key.code in PC_MODIFIERS) {
            val now = SystemClock.uptimeMillis()
            val doubleTap = lastModKey == key.code && now - lastModTap <= DOUBLE_TAP_MS
            when {
                (mods[key.code] ?: MOD_OFF) == MOD_LOCKED -> { sendKey(key.code, false); mods[key.code] = MOD_OFF }
                doubleTap -> { if ((mods[key.code] ?: MOD_OFF) != MOD_ONCE) sendKey(key.code, true); mods[key.code] = MOD_LOCKED }
                (mods[key.code] ?: MOD_OFF) == MOD_ONCE -> { sendKey(key.code, false); mods[key.code] = MOD_OFF }
                else -> { sendKey(key.code, true); mods[key.code] = MOD_ONCE }
            }
            lastModTap = now
            lastModKey = key.code
            return
        }
        sendKey(key.code, true)
    }
    fun release(key: ToolbarKey) {
        if (key.code in PC_MODIFIERS) return
        sendKey(key.code, false)
        releaseOnceModifiers()
    }
    fun releaseAll() {
        for ((code, state) in mods.toMap()) if (state != MOD_OFF) sendKey(code, false)
    }
}

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
    onPaste: () -> Unit,
    config: QuickConfigState,
    configActions: QuickConfigActions,
    onHeightChanged: (Int) -> Unit,
) {
    val pal = LocalPalette.current
    var configOpen by remember { mutableStateOf(false) }
    var barHeightPx by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val mk = remember { ModifierKeys(sendKey, scope) }
    val bottomOffset = with(LocalDensity.current) { bottomOffsetPx.toDp() }

    DisposableEffect(Unit) {
        onDispose {
            mk.releaseAll()
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
                            val state = mk.mods[key.code] ?: MOD_OFF
                            KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { mk.press(key) }, { mk.release(key) })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TOOLBAR_ROW_BOTTOM.forEach { key ->
                            val state = mk.mods[key.code] ?: MOD_OFF
                            KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { mk.press(key) }, { mk.release(key) })
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
                                val state = mk.mods[key.code] ?: MOD_OFF
                                KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { mk.press(key) }, { mk.release(key) }, 38.dp, 40.dp)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ARROW_CLUSTER_BOTTOM.forEach { key ->
                                val state = mk.mods[key.code] ?: MOD_OFF
                                KeyCap(key.label, state != MOD_OFF, state == MOD_LOCKED, { mk.press(key) }, { mk.release(key) }, 38.dp, 40.dp)
                            }
                        }
                    }
                }
            }
        }
        if (configOpen) {
            val barH = with(LocalDensity.current) { barHeightPx.toDp() }
            QuickConfigPanel(config, configActions, { configOpen = false },
                Modifier.align(Alignment.BottomEnd).padding(bottom = barH + 6.dp, end = 6.dp))
        }
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
private fun ActionCap(label: String, onClick: () -> Unit, width: Dp = 46.dp, height: Dp = 40.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(width).height(height)
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

private sealed interface VertCell {
    data class Key(val k: ToolbarKey) : VertCell
    object Paste : VertCell
    object Ime : VertCell
    object Gear : VertCell
}

// The scrollable keys of the expanded landscape bar, row-major into three columns. The arrows and
// PageUp/PageDown are NOT here: they stay in the fixed ThinkPad cluster below the grid.
private val VERT_CELLS: List<VertCell> = listOf(
    VertCell.Key(ToolbarKey("Esc", 1)), VertCell.Key(ToolbarKey("Tab", 15)), VertCell.Key(ToolbarKey("/", 53)), VertCell.Key(ToolbarKey("Del", 111)),
    VertCell.Key(ToolbarKey("Home", 102)), VertCell.Key(ToolbarKey("End", 107)),
    VertCell.Key(ToolbarKey("Shift", 42)), VertCell.Key(ToolbarKey("Ctrl", 29)), VertCell.Key(ToolbarKey("Alt", 56)), VertCell.Key(ToolbarKey("Super", 125)),
    VertCell.Paste, VertCell.Ime, VertCell.Gear,
)

// The collapsed bar is a single gear-width column of the most-used keys plus the expand handle.
private val COLLAPSED_KEYS = listOf(
    ToolbarKey("Esc", 1), ToolbarKey("Tab", 15), ToolbarKey("Ctrl", 29), ToolbarKey("Alt", 56), ToolbarKey("Super", 125),
)

/**
 * Landscape extra-keys bar docked to the right edge inside the safe area. Collapsed it is a single
 * gear-width column of the most-used keys plus an expand handle; expanded it opens a three-column
 * scrollable grid with the ThinkPad arrow/PageUp/PageDown cluster fixed below it (same arrangement as
 * the portrait toolbar). Reports its measured width so the session reserves it out of the guest output.
 */
@Composable
fun SessionKeyBarVertical(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    sendKey: (Int, Boolean) -> Unit,
    onToggleIme: () -> Unit,
    imeOpen: Boolean,
    onPaste: () -> Unit,
    config: QuickConfigState,
    configActions: QuickConfigActions,
    safeInsetTopPx: Int,
    safeInsetBottomPx: Int,
    safeInsetRightPx: Int,
    onWidthChanged: (Int) -> Unit,
) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val mk = remember { ModifierKeys(sendKey, scope) }
    var configOpen by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val topPad = with(density) { safeInsetTopPx.toDp() } + 6.dp
    val bottomPad = with(density) { safeInsetBottomPx.toDp() } + 6.dp
    val rightPad = with(density) { safeInsetRightPx.toDp() } + 6.dp
    DisposableEffect(Unit) { onDispose { mk.releaseAll(); onWidthChanged(0) } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = rightPad, top = topPad, bottom = bottomPad)
                .then(if (expanded) Modifier.fillMaxHeight() else Modifier)
                .onGloballyPositioned { onWidthChanged(it.size.width) }
                .background(Color(0xE6101418), RoundedCornerShape(12.dp))
                .border(1.dp, pal.line2, RoundedCornerShape(12.dp))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume(); waitForUpOrCancellation()?.consume() } }
                .padding(if (expanded) 6.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!expanded) {
                COLLAPSED_KEYS.forEach { key ->
                    val st = mk.mods[key.code] ?: MOD_OFF
                    KeyCap(key.label, st != MOD_OFF, st == MOD_LOCKED, { mk.press(key) }, { mk.release(key) }, 40.dp, 40.dp)
                }
                IconCap(Icons.Outlined.Keyboard, "Show keys", onToggleExpanded, width = 40.dp, height = 40.dp)
            } else {
                Row(Modifier.width(44.dp * 3 + 4.dp * 2), horizontalArrangement = Arrangement.End) {
                    IconCap(Icons.Filled.Close, "Hide keys", onToggleExpanded, width = 44.dp, height = 30.dp)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    VERT_CELLS.chunked(3).forEach { rowCells ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowCells.forEach { cell ->
                                when (cell) {
                                    is VertCell.Key -> {
                                        val st = mk.mods[cell.k.code] ?: MOD_OFF
                                        KeyCap(cell.k.label, st != MOD_OFF, st == MOD_LOCKED, { mk.press(cell.k) }, { mk.release(cell.k) }, 44.dp, 38.dp)
                                    }
                                    VertCell.Paste -> ActionCap("Paste", onPaste, 44.dp, 38.dp)
                                    VertCell.Ime -> IconCap(Icons.Outlined.Keyboard, "Toggle keyboard", onToggleIme, active = imeOpen, width = 44.dp, height = 38.dp)
                                    VertCell.Gear -> IconCap(Icons.Filled.Settings, "Quick config", { configOpen = !configOpen }, active = configOpen, width = 44.dp, height = 38.dp)
                                }
                            }
                        }
                    }
                }
                // The navigation cluster stays fixed below the scrolling grid, in the same ThinkPad
                // arrangement as the portrait toolbar: PageUp/PageDn flanking Up, inverted-T below.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ARROW_CLUSTER_TOP.forEach { key ->
                            val st = mk.mods[key.code] ?: MOD_OFF
                            KeyCap(key.label, st != MOD_OFF, st == MOD_LOCKED, { mk.press(key) }, { mk.release(key) }, 44.dp, 38.dp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ARROW_CLUSTER_BOTTOM.forEach { key ->
                            val st = mk.mods[key.code] ?: MOD_OFF
                            KeyCap(key.label, st != MOD_OFF, st == MOD_LOCKED, { mk.press(key) }, { mk.release(key) }, 44.dp, 38.dp)
                        }
                    }
                }
            }
        }
        if (configOpen) {
            QuickConfigPanel(config, configActions, { configOpen = false },
                Modifier.align(Alignment.BottomEnd).padding(end = rightPad + 150.dp, bottom = bottomPad))
        }
    }
}