package com.droidhost.launcher.hud

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal object DroidHostMark {
    const val VIEW_W = 240f
    const val VIEW_H = 240f
    private const val INK_LEFT = 20f
    private const val INK_RIGHT = 220f

    private val frame: Path by lazy {
        Path().apply {
            moveTo(209f, 137f)
            lineTo(209f, 180f)
            quadTo(209f, 209f, 180f, 209f)
            lineTo(60f, 209f)
            quadTo(31f, 209f, 31f, 180f)
            lineTo(31f, 60f)
            quadTo(31f, 31f, 60f, 31f)
            lineTo(180f, 31f)
            quadTo(209f, 31f, 209f, 60f)
            lineTo(209f, 103f)
        }
    }

    private val scaled = Path()
    private val matrix = Matrix()
    private val coreRect = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun widthFor(height: Float): Float = height * VIEW_W / VIEW_H

    fun inkLeftFor(height: Float): Float = INK_LEFT * height / VIEW_H
    fun inkRightFor(height: Float): Float = INK_RIGHT * height / VIEW_H

    fun draw(canvas: Canvas, left: Float, top: Float, height: Float, bodyColor: Int, orbColor: Int) {
        val s = height / VIEW_H
        matrix.setScale(s, s)
        matrix.postTranslate(left, top)
        frame.transform(matrix, scaled)
        stroke.color = bodyColor
        stroke.strokeWidth = 22f * s
        canvas.drawPath(scaled, stroke)
        coreRect.set(left + 74f * s, top + 74f * s, left + 166f * s, top + 166f * s)
        fill.color = orbColor
        canvas.drawRoundRect(coreRect, 23f * s, 23f * s, fill)
    }
}