package com.avip.expense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Box on the camera screen where the odometer digits should be. The same area (a little larger)
 * of the saved photo is cut out, before the GPS stamp is drawn, and read for the number.
 */
class OdometerGuideView(context: Context) : View(context) {

    companion object {
        // the box, as parts of the 4:3 camera frame (upright phone)
        const val LEFT = 0.08f; const val RIGHT = 0.92f; const val TOP = 0.30f; const val BOTTOM = 0.52f
        private const val MARGIN = 0.07f

        /** The area of a saved photo to read: the box plus a margin (whole width when the phone was sideways). */
        fun cropRect(w: Int, h: Int): Rect {
            return if (h >= w) Rect(
                (w * (LEFT - MARGIN)).toInt().coerceAtLeast(0), (h * (TOP - MARGIN)).toInt().coerceAtLeast(0),
                (w * (RIGHT + MARGIN)).toInt().coerceAtMost(w), (h * (BOTTOM + MARGIN)).toInt().coerceAtMost(h))
            else Rect((w * 0.05f).toInt(), (h * 0.18f).toInt(), (w * 0.95f).toInt(), (h * 0.70f).toInt())
        }
    }

    private val dim = Paint().apply { color = 0x66000000 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2.5f }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5A524.toInt(); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 5f; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = resources.displayMetrics.scaledDensity * 15f; isFakeBoldText = true
        textAlign = Paint.Align.CENTER; setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(c: Canvas) {
        val f = CameraFrame.rect(width, height)
        if (f.isEmpty) return
        val b = RectF(f.left + f.width() * LEFT, f.top + f.height() * TOP, f.left + f.width() * RIGHT, f.top + f.height() * BOTTOM)
        val r = resources.displayMetrics.density * 14f
        val hole = Path().apply { addRect(f, Path.Direction.CW); addRoundRect(b, r, r, Path.Direction.CCW) }
        c.drawPath(hole, dim)
        c.drawRoundRect(b, r, r, line)
        val k = b.height() * 0.28f
        listOf(
            floatArrayOf(b.left, b.top + k, b.left, b.top, b.left + k, b.top),
            floatArrayOf(b.right - k, b.top, b.right, b.top, b.right, b.top + k),
            floatArrayOf(b.left, b.bottom - k, b.left, b.bottom, b.left + k, b.bottom),
            floatArrayOf(b.right - k, b.bottom, b.right, b.bottom, b.right, b.bottom - k),
        ).forEach { p -> c.drawPath(Path().apply { moveTo(p[0], p[1]); lineTo(p[2], p[3]); lineTo(p[4], p[5]) }, corner) }
        c.drawText(tr("Odometer के अंक इस box में रखें", "Keep the odometer digits in this box"), b.centerX(), b.top - label.textSize * 0.8f, label)
    }
}
