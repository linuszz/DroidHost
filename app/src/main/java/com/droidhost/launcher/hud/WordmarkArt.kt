package com.droidhost.launcher.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.droidhost.launcher.R
import java.io.File

internal object WordmarkArt {
    private const val DROID = "Droid"
    private const val HOST = "Host"
    private const val REF = 100f

    private var regular: Typeface = Typeface.DEFAULT
    private var bold: Typeface = Typeface.DEFAULT
    private var capRatio = 0.72f
    private var ready = false

    private val droidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hostPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun init(context: Context) {
        if (ready) return
        runCatching {
            // Typeface.Builder has no res/font overload; stage the variable TTF once so
            // per-weight variation settings can be applied from a file-backed builder.
            val ttf = File(context.cacheDir, "space_grotesk_variable.ttf")
            if (!ttf.exists()) {
                context.resources.openRawResource(R.font.space_grotesk_variable).use { input ->
                    ttf.outputStream().use { input.copyTo(it) }
                }
            }
            regular = Typeface.Builder(ttf).setFontVariationSettings("'wght' 400").build()
            bold = Typeface.Builder(ttf).setFontVariationSettings("'wght' 700").build()
            val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; textSize = REF }
            val bounds = Rect()
            probe.getTextBounds("H", 0, 1, bounds)
            if (bounds.height() > 0) capRatio = bounds.height() / REF
        }
        ready = true
    }

    private fun fontSizeFor(height: Float): Float = height / capRatio

    fun widthFor(height: Float): Float {
        val fs = fontSizeFor(height)
        droidPaint.typeface = regular; droidPaint.textSize = fs
        hostPaint.typeface = bold; hostPaint.textSize = fs
        return droidPaint.measureText(DROID) + hostPaint.measureText(HOST)
    }

    fun draw(canvas: Canvas, left: Float, top: Float, height: Float, inkColor: Int, ballColor: Int) {
        val fs = fontSizeFor(height)
        droidPaint.typeface = regular; droidPaint.textSize = fs; droidPaint.color = inkColor
        hostPaint.typeface = bold; hostPaint.textSize = fs; hostPaint.color = ballColor
        val baseline = top + height
        canvas.drawText(DROID, left, baseline, droidPaint)
        canvas.drawText(HOST, left + droidPaint.measureText(DROID), baseline, hostPaint)
    }
}