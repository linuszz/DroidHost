package com.droidhost.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.droidhost.launcher.R
import com.droidhost.launcher.hud.WordmarkArt

@Composable
internal fun Wordmark(height: Dp, modifier: Modifier = Modifier, ink: Color = MaterialTheme.colorScheme.onBackground, ball: Color = LocalPalette.current.signal) {
    WordmarkArt.init(LocalContext.current)
    val name = stringResource(R.string.app_name)
    val ratio = WordmarkArt.widthFor(1f)
    Canvas(modifier.height(height).width(height * ratio).semantics { contentDescription = name }) {
        drawIntoCanvas { WordmarkArt.draw(it.nativeCanvas, 0f, 0f, size.height, ink.toArgb(), ball.toArgb()) }
    }
}