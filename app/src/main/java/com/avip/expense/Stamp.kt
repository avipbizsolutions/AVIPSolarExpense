package com.avip.expense

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.location.Location
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Draws the map + address + coordinates + time panel onto the bottom of the photo. */
object Stamp {

    fun formatLatLon(loc: Location): String =
        String.format(Locale.US, "Lat %.6f°  Long %.6f°", loc.latitude, loc.longitude)

    fun formatTime(d: Date): String =
        SimpleDateFormat("EEE, dd/MM/yyyy hh:mm a 'GMT'XXX", Locale.getDefault()).format(d)

    /** [heading] = direction the camera faced (degrees from north); adds a north arrow when set. */
    fun draw(
        photo: Bitmap,
        map: Bitmap?,
        loc: Location?,
        place: PlaceInfo,
        time: Date,
        heading: Float? = null,
        recycleMap: Boolean = true,
    ) {
        val c = Canvas(photo)
        val unit = min(photo.width, photo.height).toFloat()
        val margin = unit * 0.025f
        val pad = unit * 0.02f
        val mapSize = unit * 0.24f
        val corner = unit * 0.02f

        val panelLeft = margin
        val panelRight = photo.width - margin
        val textInset = if (map != null) mapSize + pad * 2 else pad
        val textWidth = (panelRight - panelLeft - textInset - pad).toInt().coerceAtLeast(50)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.038f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = unit * 0.027f
            setShadowLayer(unit * 0.003f, 0f, 0f, Color.BLACK)
        }

        val title = place.title.ifBlank { if (loc == null) "Location unavailable" else "AVIP GPSCam" }
        val body = buildString {
            if (place.address.isNotBlank()) appendLine(place.address)
            if (loc != null) appendLine(formatLatLon(loc))
            if (heading != null) appendLine("Facing ${Compass.label(heading)}")
            append(formatTime(time))
        }

        val titleLayout = layout(title, titlePaint, textWidth, 2)
        val bodyLayout = layout(body, bodyPaint, textWidth, 6)
        val gap = unit * 0.01f
        val textHeight = titleLayout.height + gap + bodyLayout.height

        val panelHeight = max(if (map != null) mapSize else 0f, textHeight) + pad * 2
        val panelBottom = photo.height - margin
        val panelTop = panelBottom - panelHeight

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        c.drawRoundRect(RectF(panelLeft, panelTop, panelRight, panelBottom), corner, corner, bg)

        if (map != null) {
            val mapTop = panelTop + (panelHeight - mapSize) / 2f
            val dst = RectF(panelLeft + pad, mapTop, panelLeft + pad + mapSize, mapTop + mapSize)
            c.save()
            c.clipPath(Path().apply { addRoundRect(dst, corner, corner, Path.Direction.CW) })
            c.drawBitmap(map, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            if (recycleMap) map.recycle()
        }

        c.save()
        c.translate(panelLeft + textInset, panelTop + (panelHeight - textHeight) / 2f)
        titleLayout.draw(c)
        c.translate(0f, titleLayout.height + gap)
        bodyLayout.draw(c)
        c.restore()

        // North symbol in the top-right corner of the photo.
        if (heading != null) {
            val radius = unit * 0.07f
            Compass.drawNorthArrow(
                c,
                photo.width - margin - radius,
                margin + radius,
                radius,
                heading,
            )
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}

/**
 * Where the camera image sits inside a view that shows it with FIT_START:
 * a 4:3 frame (3:4 when upright), aligned to the top-left. Photos and videos
 * are both 4:3, so this is exactly the area that gets saved.
 */
object CameraFrame {
    fun rect(viewW: Int, viewH: Int): RectF {
        if (viewW <= 0 || viewH <= 0) return RectF()
        val ratio = if (viewH >= viewW) 4f / 3f else 3f / 4f // height / width
        var w = viewW.toFloat()
        var h = w * ratio
        if (h > viewH) {
            h = viewH.toFloat()
            w = h / ratio
        }
        return RectF(0f, 0f, w, h)
    }
}

/** Live preview of the GPS stamp, drawn exactly where it will appear in the photo/video. */
class StampPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private var frame = RectF()

    /**
     * How far the saved picture is turned relative to the screen (0, 90, 180, 270).
     * When the phone is held sideways the photo is landscape, so the stamp is
     * drawn for a landscape picture and turned to line up with it.
     */
    var rotationDeg = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        frame = CameraFrame.rect(w, h)
        bitmap?.recycle()
        bitmap = null
    }

    fun render(map: Bitmap?, loc: Location?, place: PlaceInfo, time: Date, heading: Float?) {
        val sideways = rotationDeg == 90 || rotationDeg == 270
        val fw = (if (sideways) frame.height() else frame.width()).toInt()
        val fh = (if (sideways) frame.width() else frame.height()).toInt()
        if (fw <= 0 || fh <= 0) return
        val bmp = bitmap?.takeIf { it.width == fw && it.height == fh }
            ?: Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888).also {
                bitmap?.recycle()
                bitmap = it
            }
        bmp.eraseColor(Color.TRANSPARENT)
        Stamp.draw(bmp, map, loc, place, time, heading, recycleMap = false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.save()
        canvas.translate(frame.centerX(), frame.centerY())
        canvas.rotate(rotationDeg.toFloat())
        canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, null)
        canvas.restore()
    }
}
