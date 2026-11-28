package com.droidhost.launcher.ui

import android.content.res.Configuration
import androidx.compose.ui.unit.Density
import com.droidhost.launcher.core.AppUiPrefs
import kotlin.math.roundToInt

internal fun scaledAppDensity(base: Density, percent: Int, fontPercent: Int = 100): Density {
    val scale = AppUiPrefs.normalizeScale(percent) / 100f
    val fontScale = base.fontScale * (fontPercent / 100f)
    return if (scale == 1f && fontScale == base.fontScale) base else Density(base.density * scale, fontScale)
}

internal fun scaledAppConfiguration(base: Configuration, percent: Int): Configuration {
    val scale = AppUiPrefs.normalizeScale(percent) / 100f
    if (scale == 1f) return base
    return Configuration(base).apply {
        if (densityDpi > 0) densityDpi = (densityDpi * scale).roundToInt()
        if (screenWidthDp > 0) screenWidthDp = (screenWidthDp / scale).roundToInt()
        if (screenHeightDp > 0) screenHeightDp = (screenHeightDp / scale).roundToInt()
        if (smallestScreenWidthDp > 0) smallestScreenWidthDp = (smallestScreenWidthDp / scale).roundToInt()
    }
}
