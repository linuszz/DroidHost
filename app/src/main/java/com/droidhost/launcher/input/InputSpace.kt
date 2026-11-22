package com.droidhost.launcher.input

object InputSpace {
    const val WIDTH = 1920
    const val HEIGHT = 1080

    fun x(position: Float, viewWidth: Int): Int = scale(position, viewWidth.toFloat(), WIDTH)

    fun y(position: Float, viewHeight: Int): Int = scale(position, viewHeight.toFloat(), HEIGHT)

    fun x(position: Float, extent: Float): Int = scale(position, extent, WIDTH)

    fun y(position: Float, extent: Float): Int = scale(position, extent, HEIGHT)

    private fun scale(position: Float, extent: Float, size: Int): Int =
        if (extent <= 0f) 0 else (position / extent * size).toInt().coerceIn(0, size - 1)
}
