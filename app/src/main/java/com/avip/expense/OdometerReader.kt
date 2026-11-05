package com.avip.expense

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Reads the odometer number from a photo, on the phone (Google ML Kit).
 *
 * How it gets it right more often:
 *  - photos from the app camera come with a clean crop of the guide box, saved before the GPS stamp
 *    was drawn: that is read first, so stamp text (dates, coordinates, PIN code) cannot get in the way;
 *  - each image is read several ways (as is, enlarged, high contrast, and inverted for LCD displays);
 *  - letters that look like digits are fixed (O→0, I→1, S→5, B→8 …) and a tenths digit is dropped;
 *  - candidates are scored: big characters, 4–6 digits, seen in several passes, and – most of all –
 *    making sense with the other reading (end > start, a trip of at most 1500 km).
 *
 * It is a suggestion: the person checks it against the photo and can correct it or tap an alternative.
 * Call from a background thread.
 */
object OdometerReader {
    const val CROP_SUFFIX = ".odo.jpg"
    private const val MAX_TRIP = 1500.0

    data class Result(val value: Long, val sure: Boolean, val alternatives: List<Long>)

    /**
     * @param after when reading the end odometer: the start reading (the result should be above it).
     * @param near when reading the start odometer: the last end reading known for this person (likely close).
     */
    fun read(path: String, after: Double? = null, near: Double? = null): Result? {
        if (!File(path).exists()) return null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val hits = mutableListOf<Hit>()
            val crop = File(path + CROP_SUFFIX)
            if (crop.exists()) decode(crop.path, 1600)?.let { hits += passes(recognizer, it, fromCrop = true) }
            // nothing good from the box (or a gallery photo): the whole photo, without the stamp at the bottom
            if (hits.none { it.digits in 4..7 }) decode(path, 1800)?.let { full ->
                val top = Bitmap.createBitmap(full, 0, 0, full.width, (full.height * 0.72f).toInt())
                hits += passes(recognizer, top, fromCrop = false)
                if (top !== full) full.recycle()
            }
            pick(hits, after, near)
        } catch (_: Throwable) {
            null
        } finally {
            recognizer.close()
        }
    }

    private data class Hit(val value: Long, val digits: Int, val height: Float, val fromCrop: Boolean)

    private fun decode(path: String, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    /** The same picture read four ways. */
    private fun passes(rec: TextRecognizer, bmp: Bitmap, fromCrop: Boolean): List<Hit> {
        val out = mutableListOf<Hit>()
        val base = if (bmp.width < 1000) Bitmap.createScaledBitmap(bmp, 1000, (1000f * bmp.height / bmp.width).toInt(), true) else bmp
        val variants = listOf(base, filtered(base, contrast = true, invert = false), filtered(base, contrast = true, invert = true))
        for (v in variants) {
            out += recognize(rec, v, fromCrop)
            if (v !== base) v.recycle()
        }
        if (base !== bmp) base.recycle()
        return out
    }

    /** Grey, strong contrast; optionally inverted (light digits on a dark LCD). */
    private fun filtered(src: Bitmap, contrast: Boolean, invert: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        if (contrast) {
            val k = 1.9f; val t = (-0.5f * k + 0.5f) * 255f
            cm.postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        }
        if (invert) cm.postConcat(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private val lookAlike = mapOf('O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0', 'U' to '0', 'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', '!' to '1',
        'S' to '5', 's' to '5', 'B' to '8', 'Z' to '2', 'z' to '2', 'G' to '6', 'b' to '6', 'T' to '7', 'g' to '9', 'q' to '9', 'A' to '4')

    private fun recognize(rec: TextRecognizer, bmp: Bitmap, fromCrop: Boolean): List<Hit> {
        val text = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS)
        val out = mutableListOf<Hit>()
        val h = bmp.height.toFloat()
        for (block in text.textBlocks) for (line in block.lines) {
            // a line, and each word on its own (an odometer is often split from "km" or the trip meter)
            val pieces = listOf(line.text to (line.boundingBox?.height() ?: 0)) + line.elements.map { it.text to (it.boundingBox?.height() ?: 0) }
            for ((raw, bh) in pieces) {
                var t = raw.trim().replace(Regex("(?i)\\s*(km|kms|odo|total)\\s*$"), "").replace(Regex("(?i)^(odo|total)\\s*"), "")
                if (t.isEmpty()) continue
                val digitShare = t.count { it.isDigit() }.toFloat() / t.count { !it.isWhitespace() }.coerceAtLeast(1)
                if (digitShare < 0.6f) continue // a word, a date, coordinates …
                if (t.contains(':') || t.contains('/') || t.contains('°')) continue
                t = t.map { lookAlike[it] ?: it }.joinToString("")
                // a tenths digit after a point or comma is dropped: "12540.3" → 12540
                t = t.replace(Regex("[.,]\\d$"), "")
                val digits = t.replace(Regex("[\\s'`]"), "")
                if (!digits.all { it.isDigit() } || digits.length !in 3..7) continue
                val v = digits.toLongOrNull() ?: continue
                if (v <= 0) continue
                out += Hit(v, digits.length, bh / h, fromCrop)
            }
        }
        return out
    }

    private fun pick(hits: List<Hit>, after: Double?, near: Double?): Result? {
        if (hits.isEmpty()) return null
        val byValue = hits.groupBy { it.value }
        val scored = byValue.map { (v, list) ->
            val best = list.maxOf { it.height }
            val d = list.first().digits
            var score = best * 100f                                // bigger characters
            score += when (d) { 5, 6 -> 25f; 4 -> 18f; 7 -> 8f; else -> 0f } // usual odometer length
            score += (list.size - 1).coerceAtMost(4) * 6f          // seen in several passes
            if (list.any { it.fromCrop }) score += 12f             // inside the guide box
            if (after != null && after > 0) {
                val trip = v - after
                score += if (trip > 0 && trip <= MAX_TRIP) 60f - (trip / MAX_TRIP * 20f).toFloat() else -80f
            }
            if (near != null && near > 0) {
                val diff = v - near
                score += if (diff >= 0 && diff <= 3000) 35f else if (abs(diff) <= 3000) 10f else 0f
            }
            v to score
        }.sortedByDescending { it.second }
        val (top, s1) = scored.first()
        val s2 = scored.getOrNull(1)?.second ?: -999f
        val plausible = after == null || after <= 0 || (top - after > 0 && top - after <= MAX_TRIP)
        return Result(top, sure = plausible && s1 - s2 >= 15f, alternatives = scored.drop(1).take(3).map { it.first }.filter { it != top })
    }
}
