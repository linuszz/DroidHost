package com.droidhost.launcher.ui

import androidx.compose.ui.platform.testTag
import com.droidhost.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.droidhost.launcher.session.SessionPrefs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import com.droidhost.launcher.core.PhantomProcessLimit


internal val AttentionAmber = Color(0xFFFFB547)

@Composable
internal fun BottomBar(
    s: FrontEndState, selected: String,
    onSelect: (String) -> Unit, onFocusSelect: (String) -> Unit, a: FrontEndActions, modifier: Modifier,
) {
    val shown = Hops.railPreview ?: selected
    val keys = navigationKeys(s)
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val tint = androidx.compose.runtime.rememberUpdatedState(LocalPalette.current.signal)
    val content = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val barContext = LocalContext.current
    remember { RailLabels.load(barContext) }
    val iconOnly = !RailLabels.shown
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // The prototype's nav item is flex:1: the entries share the bar's width evenly.
            val itemWidth = if (iconOnly) (maxWidth / keys.size).coerceIn(56.dp, 88.dp)
                else maxWidth / keys.size
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).onPlaced { content[0] = it }) {
                if (shown in keys) ChipGlide(keys.indexOf(shown), bounds[shown], tint, GlideAxis.Horizontal, corner = 14.dp, fill = 0.14f, outline = 0f)
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.Center) {
                    NavigationItems(s, shown, compact = true, iconOnly = iconOnly, height = 48.dp, width = itemWidth,
                        place = { key -> Modifier.onGloballyPositioned { item -> content[0]?.let { bounds[key] = it.localBoundingBoxOf(item) }; Hops.railBounds[key] = item.boundsInRoot() } },
                        onSelect = onSelect, onFocusSelect = onFocusSelect)
                }
            }
        }
        if (s.running != null) ResumeRailItem(s.running, compact = true, iconOnly = iconOnly, a.onResume, inBar = true)
    }
}

internal fun navigationKeys(s: FrontEndState) = NavTabs.bar(s)

internal object RailLabels {
    var shown by mutableStateOf(true)
        private set

    fun load(context: android.content.Context) {
        shown = SessionPrefs.railLabels(context)
    }

    fun set(context: android.content.Context, show: Boolean) {
        shown = show
        SessionPrefs.setRailLabels(context, show)
    }
}

@Composable
internal fun SideRail(
    s: FrontEndState, selected: String,
    onSelect: (String) -> Unit, onFocusSelect: (String) -> Unit, a: FrontEndActions, modifier: Modifier,
) {
    val pal = LocalPalette.current
    val compact = LocalConfiguration.current.screenHeightDp < 420
    val railContext = LocalContext.current
    remember { RailLabels.load(railContext) }
    val iconOnly = isNarrowScreen() || !RailLabels.shown
    val glass = LocalGlassActive.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(if (iconOnly) 64.dp else 92.dp)
            .then(
                if (glass) Modifier.background(pal.glassScrim).background(pal.glassBg).drawBehind {
                    val w = 1.dp.toPx()
                    drawRect(
                        Brush.radialGradient(listOf(pal.glassSpec, Color.Transparent), center = Offset(size.width / 2f, -size.height * 0.2f), radius = size.width * 0.6f),
                    )
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.12f to pal.glassLine, 0.88f to pal.glassLine, 1f to Color.Transparent),
                        topLeft = Offset(size.width - w, 0f), size = Size(w, size.height),
                    )
                }
                else Modifier.background(Brush.verticalGradient(listOf(pal.cardTop, pal.cardBot)))
                    .machinedBevel()
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 0.12f to pal.hair, 0.88f to pal.hair, 1f to Color.Transparent),
                            topLeft = Offset(size.width - w, 0f), size = Size(w, size.height),
                        )
                    },
            )
            .padding(vertical = if (compact) 8.dp else 12.dp),
    ) {
        val count = navigationKeys(s).size
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val fit = (maxHeight / count - 4.dp).coerceIn(44.dp, if (iconOnly) 48.dp else if (compact) 52.dp else 60.dp)
        val shown = Hops.railPreview ?: selected
        val keys = navigationKeys(s)
        val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
        val tint = androidx.compose.runtime.rememberUpdatedState(LocalPalette.current.signal)
        val content = remember { arrayOfNulls<LayoutCoordinates>(1) }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).onPlaced { content[0] = it }) {
        if (shown in keys) ChipGlide(keys.indexOf(shown), bounds[shown], tint, GlideAxis.Vertical, corner = 14.dp, fill = 0.14f, outline = 0f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            val place: (String) -> Modifier = { key ->
                Modifier.onGloballyPositioned { item ->
                    content[0]?.let { bounds[key] = it.localBoundingBoxOf(item) }
                    Hops.railBounds[key] = item.boundsInRoot()
                }
            }
            NavigationItems(s, shown, compact, iconOnly, fit, if (iconOnly) 52.dp else 80.dp, place, onSelect, onFocusSelect)
        }
        }
        }
        var lastRunning by remember { mutableStateOf("") }
        if (s.running != null) lastRunning = s.running
        AnimatedVisibility(
            s.running != null,
            enter = expandVertically(Motion.sp(0.75f)) + fadeIn(Motion.tw(300)),
            exit = shrinkVertically(Motion.tw(220)) + fadeOut(Motion.tw(180)),
        ) { ResumeRailItem(lastRunning, compact, iconOnly, a.onResume) }
    }
}

internal fun railLabelIcon(key: String): Pair<Int, ImageVector> = when (key) {
    "steam" -> R.string.rail_steam to Icons.Outlined.SportsEsports
    "games" -> R.string.rail_games to Icons.Outlined.VideoLibrary
    "soundtracks" -> R.string.rail_music to SoundtrackIcon
    "stores" -> R.string.rail_stores to Icons.Outlined.LocalMall
    // The design rail names this entry 虚拟机 (design §4.2); the session-mode label keeps 桌面.
    "desktop" -> R.string.vm_title to Icons.Outlined.DesktopWindows
    "components" -> R.string.rail_components to Icons.Outlined.Layers
    "agent" -> R.string.rail_agent to Icons.Outlined.AutoAwesome
    "android-apps" -> R.string.rail_apps to Icons.Outlined.Apps
    "setup" -> R.string.rail_setup to Icons.Outlined.Tune
    "system" -> R.string.rail_system to Icons.Outlined.Settings
    NavTabs.MORE -> R.string.rail_more to Icons.Outlined.MoreHoriz
    else -> R.string.rail_updates to Icons.Outlined.SystemUpdate
}

// The shared runtime is optional legacy: its update is shown on the setup page, not as a rail badge.
internal fun setupNeedsAttention(s: FrontEndState): Boolean =
    PhantomProcessLimit.blocksSteam(s.phantomProcessStatus) || com.droidhost.launcher.sync.Syncthing.needsAttention

internal fun railBadge(s: FrontEndState, key: String, setupNeedsAttention: Boolean = setupNeedsAttention(s)): Boolean = when (key) {
    "setup" -> setupNeedsAttention
    "system" -> s.updates.hasUpdate || setupNeedsAttention
    "updates" -> s.updates.hasUpdate
    NavTabs.MORE -> NavTabs.more(s).any { it != NavTabs.MORE && railBadge(s, it, setupNeedsAttention) }
    else -> false
}

internal fun railCount(s: FrontEndState, key: String): Int = when (key) {
    "stores" -> s.storeDownloadsActive
    NavTabs.MORE -> if ("stores" in NavTabs.more(s)) s.storeDownloadsActive else 0
    else -> 0
}

@Composable
private fun NavigationItems(
    s: FrontEndState, shown: String, compact: Boolean, iconOnly: Boolean, height: Dp, width: Dp,
    place: (String) -> Modifier, onSelect: (String) -> Unit, onFocusSelect: (String) -> Unit,
) {
    val setupNeedsAttention = setupNeedsAttention(s)
    for (key in navigationKeys(s)) {
        val (label, icon) = railLabelIcon(key)
        key(key) {
            RailItem(stringResource(label), icon, key, shown == key, compact, iconOnly, height, width,
                badge = railBadge(s, key, setupNeedsAttention),
                count = railCount(s, key),
                place = place, onFocus = { onFocusSelect(key) }) { onSelect(key) }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RailItem(
    label: String, icon: ImageVector, key: String, current: Boolean, compact: Boolean, iconOnly: Boolean, height: Dp, width: Dp,
    badge: Boolean = false,
    count: Int = 0,
    place: (String) -> Modifier = { Modifier },
    onFocus: () -> Unit = {}, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val frontFocus = LocalFrontFocus.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    if (frontFocus != null) LaunchedEffect(focused) {
        if (focused) {
            frontFocus.focusedRail = key
            onFocus()
        }
        else if (frontFocus.focusedRail == key) frontFocus.focusedRail = null
    }
    val glass = LocalGlassActive.current
    val fg by animateColorAsState(
        when {
            current -> if (glass) ThemeColors.inkOn(pal.signal, pal.glassWorst) else pal.signal
            focused || hovered -> colors.onBackground
            else -> if (glass) ThemeColors.inkOn(colors.onSurfaceVariant, pal.glassWorst) else colors.onSurfaceVariant
        },
        Motion.tw(220), label = "railFg",
    )
    val fill by animateColorAsState(
        if (current) Color.Transparent else if (focused || hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent,
        Motion.tw(220), label = "railFill",
    )
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "railScale")
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(current) { if (current) bringIntoView.bringIntoView() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (frontFocus != null) Modifier.focusRequester(frontFocus.railFor(key)) else Modifier)
            .testTag("rail-$key")
            .then(place(key))
            .landingPop(Hops.railLanded[key] ?: 0)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(width = width, height = height)
            .bringIntoViewRequester(bringIntoView)
            .clip(Shape14)
            .background(fill)
            // The prototype marks the selected rail entry with its mint tile alone; the rail is
            // also the page selector, so the tile moves with keyboard and gamepad focus and the
            // extra focus outline only duplicated it.
            .glideBorder(focused && !current, Shape14, pal.signal)
            .hoverable(src)
            .selectable(selected = current, interactionSource = src, indication = LocalIndication.current, role = Role.Tab, onClick = onClick)
            .then(if (iconOnly) Modifier.semantics { contentDescription = label } else Modifier),
    ) {
        if (iconOnly) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The prototype's nav item is a 20px glyph inside a 24px tile over a 10px label (§4.2);
            // the active tile carries the mint wash and its 1px inner highlight.
            Box(
                Modifier.size(if (compact) 24.dp else 26.dp)
                    .testTag("rail-tile-$key")
                    .then(
                        if (current) Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(pal.primary.copy(alpha = 0.18f), pal.primary.copy(alpha = 0.08f)),
                                ),
                            )
                            // The prototype's active tile carries a 12px mint glow.
                            .shadow(
                                12.dp, RoundedCornerShape(8.dp),
                                spotColor = pal.primary.copy(alpha = 0.12f),
                                ambientColor = Color.Transparent,
                            )
                            .drawBehind {
                                drawRect(
                                    Color.White.copy(alpha = 0.08f), topLeft = Offset(0f, 0f),
                                    size = Size(size.width, 1.dp.toPx()),
                                )
                            }
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (compact) 20.dp else 22.dp))
            }
            FitText(
                label, maxSize = if (compact) 10.sp else 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
                fontWeight = if (compact) FontWeight.Normal else FontWeight.SemiBold, color = fg,
            )
        }
        if (badge) Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (iconOnly) 7.dp else 8.dp, end = if (iconOnly) 9.dp else 18.dp)
                .size(8.dp).clip(CircleShape).background(AttentionAmber),
        )
        if (count > 0) Text(
            if (count > 9) "9+" else count.toString(),
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = pal.onSignal, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (iconOnly) 4.dp else 6.dp, end = if (iconOnly) 5.dp else 12.dp)
                .landingPop().clip(RoundedCornerShape(8.dp)).background(pal.signal).padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ResumeRailItem(name: String, compact: Boolean, iconOnly: Boolean, onResume: () -> Unit, inBar: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val resumeDescription = stringResource(R.string.rail_resume_named, name)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulse.animateFloat(0.4f, 1.6f, infiniteRepeatable(tween(1600, easing = Motion.Ease), RepeatMode.Restart), label = "ring")
    val still = Motion.scale == 0f
    val dot: @Composable () -> Unit = {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (!still) Box(modifier = Modifier.size(14.dp).graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = (1.6f - ringScale) / 1.2f }.border(1.5.dp, pal.good, CircleShape))
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(pal.good))
        }
    }
    if (inBar) {
        // Mirror the nav entry's vertical rhythm so the dot lands on the glyph row: a 24dp tile over a
        // label-height line centred in the 48dp slot (labels), or a centred tile (icon-only). Centring
        // the dot in the whole capsule instead sat it a label-height below every other icon.
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(48.dp)
                .testTag("rail-resume")
                .clip(Shape14)
                .hoverable(src)
                .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onResume)
                .semantics { contentDescription = resumeDescription },
            contentAlignment = Alignment.Center,
        ) {
            val tile: @Composable () -> Unit = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("rail-resume-tile")
                        .clip(RoundedCornerShape(8.dp))
                        .background(pal.good.copy(alpha = if (focused || hovered) 0.20f else 0.12f))
                        .glideBorder(focused, RoundedCornerShape(8.dp), pal.signal),
                    contentAlignment = Alignment.Center,
                ) { dot() }
            }
            if (iconOnly) tile()
            else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tile()
                Text("", fontSize = 10.sp, fontWeight = FontWeight.Normal, maxLines = 1, softWrap = false, color = Color.Transparent)
            }
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .padding(top = 6.dp)
                .width(if (iconOnly) 52.dp else 80.dp)
                .clip(Shape14)
                .background(pal.good.copy(alpha = if (focused || hovered) 0.20f else 0.12f))
                .glideBorder(focused, Shape14, pal.signal)
                .hoverable(src)
                .clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onResume)
                .semantics { contentDescription = resumeDescription }
                .padding(vertical = if (iconOnly) 15.dp else if (compact) 6.dp else 9.dp, horizontal = 4.dp),
        ) {
            dot()
            if (!iconOnly) {
                Text(stringResource(R.string.resume_session), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, softWrap = false)
                Text(name, fontSize = 12.sp, color = if (LocalGlassActive.current) ThemeColors.inkOn(colors.onSurfaceVariant, pal.glassWorst) else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
