package com.avip.expense

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object Compass {
    private val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun direction(deg: Float): String = names[(((deg + 22.5f) % 360f) / 45f).toInt() % 8]

    fun label(deg: Float): String = "${deg.roundToInt() % 360}° ${direction(deg)}"

    /**
     * Draws a north arrow: red half points to north. [heading] is where the
     * camera faces (0 = north), so the arrow is rotated by -heading.
     */
    fun drawNorthArrow(c: Canvas, cx: Float, cy: Float, radius: Float, heading: Float) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.06f
        }
        c.drawCircle(cx, cy, radius, bg)
        c.drawCircle(cx, cy, radius, ring)

        c.save()
        c.rotate(-heading, cx, cy)
        val tip = radius * 0.62f
        val half = radius * 0.22f
        val north = Path().apply {
            moveTo(cx, cy - tip); lineTo(cx - half, cy); lineTo(cx + half, cy); close()
        }
        val south = Path().apply {
            moveTo(cx, cy + tip); lineTo(cx - half, cy); lineTo(cx + half, cy); close()
        }
        c.drawPath(north, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) })
        c.drawPath(south, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = radius * 0.34f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        c.drawText("N", cx, cy - tip - radius * 0.04f, letter)
        c.restore()
    }
}

/**
 * Reports the compass direction the camera is pointing at (degrees from north),
 * using the phone's rotation-vector sensor. When the phone is flat, it reports
 * where the top of the phone points instead, like a normal compass.
 */
class HeadingProvider(context: Context, private val onHeading: (Float) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    private var smoothX = 0f
    private var smoothY = 0f
    private var initialised = false

    /** Magnetic declination at the current location, to report true north. */
    var declination = 0f

    var heading: Float? = null
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Camera looks along the phone's -Z axis; world x = east, y = north.
        var east = -rotation[2]
        var north = -rotation[5]
        if (hypot(east, north) < 0.35f) {
            // Pointing mostly up/down: use the top edge of the phone instead.
            east = rotation[1]
            north = rotation[4]
        }
        val angle = atan2(east, north)
        val x = sin(angle)
        val y = cos(angle)
        if (!initialised) {
            smoothX = x; smoothY = y; initialised = true
        } else {
            smoothX += 0.15f * (x - smoothX)
            smoothY += 0.15f * (y - smoothY)
        }
        var deg = Math.toDegrees(atan2(smoothX, smoothY).toDouble()).toFloat() + declination
        deg = ((deg % 360f) + 360f) % 360f
        heading = deg
        onHeading(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Rule-of-thirds grid plus a level line in the middle. The level line stays
 * parallel to the real horizon and turns green when the phone is straight.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        strokeWidth = 1f * density
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val anglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }

    /** Phone tilt relative to the screen, in degrees; null hides the level. */
    private var roll: Float? = null

    fun setRoll(value: Float?) {
        roll = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw over the saved area only (the camera frame), not the whole screen.
        val f = CameraFrame.rect(width, height)
        if (f.isEmpty) return
        canvas.save()
        canvas.translate(f.left, f.top)
        drawGrid(canvas, f.width(), f.height())
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        for (i in 1..2) {
            canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, line)
            canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, line)
        }

        val r = roll ?: return
        val cx = w / 2f
        val cy = h / 2f
        // Distance to the nearest straight orientation (portrait or landscape).
        val off = r - 90f * Math.round(r / 90f)
        val level = kotlin.math.abs(off) < 1f
        val half = min(w, h) * 0.18f

        // Fixed white reference ticks either side of the centre.
        val gap = half + 8f * density
        canvas.drawLine(cx - gap - 18f * density, cy, cx - gap, cy, refPaint)
        canvas.drawLine(cx + gap, cy, cx + gap + 18f * density, cy, refPaint)

        // Horizon line (rotates against the phone's tilt).
        levelPaint.color = if (level) Color.rgb(76, 217, 100) else Color.rgb(255, 213, 79)
        canvas.save()
        canvas.rotate(if (level) -90f * Math.round(r / 90f) else -r, cx, cy)
        canvas.drawLine(cx - half, cy, cx + half, cy, levelPaint)
        canvas.restore()

        val text = if (level) "0°" else "${kotlin.math.abs(off).roundToInt()}°"
        anglePaint.color = levelPaint.color
        canvas.drawText(text, cx, cy - 14f * density, anglePaint)
    }
}

/**
 * Reports how far the phone is tilted (rolled) relative to the screen, from
 * the gravity sensor. Reports null when the phone is lying flat.
 */
class TiltProvider(context: Context, private val onRoll: (Float?) -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val g = FloatArray(3)
    private var initialised = false

    /** Screen rotation in degrees (0, 90, 180, 270). */
    var screenRotation = 0

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!initialised) {
            event.values.copyInto(g, 0, 0, 3)
            initialised = true
        } else {
            for (i in 0..2) g[i] += 0.2f * (event.values[i] - g[i])
        }
        val norm = kotlin.math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
        if (norm < 1f || kotlin.math.abs(g[2]) / norm > 0.85f) {
            onRoll(null) // flat: pointing at the floor or the sky
            return
        }
        var roll = Math.toDegrees(atan2(-g[0], g[1]).toDouble()).toFloat() + screenRotation
        roll = ((roll + 180f) % 360f + 360f) % 360f - 180f
        onRoll(roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
