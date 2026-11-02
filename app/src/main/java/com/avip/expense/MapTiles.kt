package com.avip.expense

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Builds a small map thumbnail from free OpenStreetMap tiles (no API key).
 * Follows the OSM tile policy: identifies the app via User-Agent, caches tiles,
 * and shows "© OpenStreetMap" attribution on every map.
 */
object MapTiles {
    private const val TILE = 256
    private const val USER_AGENT = "AVIPSolarExpense/1.0 (AVIP Solar expense app)"
    private val cache = LruCache<String, Bitmap>(32)

    fun render(lat: Double, lon: Double, zoom: Int, sizePx: Int): Bitmap? {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val px = (lon + 180.0) / 360.0 * n * TILE
        val py = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n * TILE
        val left = px - sizePx / 2.0
        val top = py - sizePx / 2.0

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.rgb(230, 230, 230))

        val tx0 = floor(left / TILE).toInt()
        val tx1 = floor((left + sizePx - 1) / TILE).toInt()
        val ty0 = floor(top / TILE).toInt()
        val ty1 = floor((top + sizePx - 1) / TILE).toInt()

        var anyTile = false
        for (ty in ty0..ty1) {
            if (ty !in 0 until n) continue
            for (tx in tx0..tx1) {
                val wrappedX = ((tx % n) + n) % n
                val tile = fetch(zoom, wrappedX, ty) ?: continue
                anyTile = true
                canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            }
        }
        if (!anyTile) {
            out.recycle()
            return null // offline: stamp is drawn without a map
        }

        drawPin(canvas, sizePx / 2f, sizePx / 2f, sizePx.toFloat())
        drawAttribution(canvas, sizePx.toFloat())
        return out
    }

    private fun fetch(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        return try {
            val conn = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                if (conn.responseCode != 200) null
                else conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also { cache.put(key, it) }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawPin(c: Canvas, cx: Float, tipY: Float, size: Float) {
        val r = size * 0.05f
        val headY = tipY - r * 1.8f
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 38, 38) }
        val path = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - r * 0.85f, headY + r * 0.5f)
            lineTo(cx + r * 0.85f, headY + r * 0.5f)
            close()
        }
        c.drawPath(path, red)
        c.drawCircle(cx, headY, r, red)
        c.drawCircle(cx, headY, r * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    }

    private fun drawAttribution(c: Canvas, size: Float) {
        val text = "© OpenStreetMap"
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 40)
            textSize = size * 0.05f
        }
        val w = p.measureText(text)
        val pad = size * 0.012f
        val bg = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        c.drawRect(size - w - pad * 2, size - p.textSize - pad * 2, size, size, bg)
        c.drawText(text, size - w - pad, size - pad - p.descent(), p)
    }
}
