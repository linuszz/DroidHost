package com.droidhost.launcher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droidhost.launcher.R
import com.droidhost.launcher.runtime.FlatpakManager

/**
 * Installed Flathub apps as a launch strip beneath the machine dock: one tap opens an app in the
 * host desktop. A supplement to the machines, not a replacement — the strip renders nothing when
 * no app is installed, so a machine-only setup looks exactly as before.
 */
@Composable
internal fun FlatpakAppStrip(
    apps: List<FlatpakManager.App>,
    onOpen: (String, String) -> Unit,
    onStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (apps.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val start = if (LocalNarrowPane.current) 20.dp else 44.dp
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            stringResource(R.string.flatpak_apps_section).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = start, bottom = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = start, end = 20.dp, top = 2.dp, bottom = 4.dp),
        ) {
            for (app in apps) {
                AppTile(app, first = app.id == apps.firstOrNull()?.id) { onOpen(app.id, app.name) }
            }
            StoreTile { onStore() }
        }
    }
}

@Composable
private fun AppTile(app: FlatpakManager.App, first: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (hot) 1.03f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "flatpakTile")
    val shape = RoundedCornerShape(14.dp)
    val iconH = 56.dp
    val icon = app.icon
    Column(
        Modifier.width(88.dp).paneItem("tile:flatpak:${app.id}").then(if (first) Modifier.firstTile() else Modifier)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onClick() }
            .controllerConfirm { onClick() }
            .semantics { contentDescription = app.name },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(iconH)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(colors.surfaceVariant),
        ) {
            if (icon != null) {
                AsyncImage(model = icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(iconH * 0.7f))
            } else {
                Icon(Icons.Outlined.Apps, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(iconH * 0.5f))
            }
        }
        Text(
            app.name, fontSize = 11.sp, lineHeight = 14.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun StoreTile(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else if (hot) 1.03f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "flatpakStoreTile")
    val shape = RoundedCornerShape(14.dp)
    val iconH = 56.dp
    val label = stringResource(R.string.flathub_name)
    Column(
        Modifier.width(88.dp).paneItem("tile:flatpak:store")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape).background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button) { onClick() }
            .controllerConfirm { onClick() }
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(iconH)
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(colors.surfaceVariant),
        ) {
            Icon(Icons.Outlined.Storefront, null, tint = pal.signal, modifier = Modifier.size(iconH * 0.5f))
        }
        Text(
            label, fontSize = 11.sp, lineHeight = 14.sp, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}