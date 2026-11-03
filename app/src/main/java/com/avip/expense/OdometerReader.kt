package com.avip.expense

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Reads the odometer number from a photo, on the phone (Google ML Kit).
 * It is a suggestion: the person checks it against the photo and can correct it.
 * Call from a background thread.
 */
object OdometerReader {

    /** The most likely odometer reading in the photo, or null when none is found. */
    fun read(path: String): Long? {
        val f = File(path)
        if (!f.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = try { Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 20, TimeUnit.SECONDS) } finally { recognizer.close() }

            // Odometer digits: a line of only digits (spaces allowed, "km" allowed), 3–7 digits.
            // The GPS stamp lines carry letters, °, : and dates, so they drop out.
            // Of what is left, the biggest characters win (the odometer is the main subject).
            data class Hit(val value: Long, val height: Int, val digits: Int)
            val hits = mutableListOf<Hit>()
            for (block in result.textBlocks) for (line in block.lines) {
                val raw = line.text.trim().replace(Regex("(?i)\\s*km\\s*$"), "")
                if (!Regex("^[0-9OoIl ]{3,12}$").matches(raw)) continue
                val digits = raw.replace(" ", "").replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
                if (digits.length !in 3..7 || !digits.all { it.isDigit() }) continue
                val v = digits.toLongOrNull() ?: continue
                if (v <= 0) continue
                hits.add(Hit(v, line.boundingBox?.height() ?: 0, digits.length))
            }
            hits.sortedWith(compareByDescending<Hit> { it.height }.thenByDescending { it.digits }).firstOrNull()?.value
        } catch (_: Throwable) {
            null
        }
    }
}
