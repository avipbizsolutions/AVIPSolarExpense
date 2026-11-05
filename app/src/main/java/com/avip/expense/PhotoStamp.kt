package com.avip.expense

import androidx.exifinterface.media.ExifInterface
import java.security.MessageDigest
import java.util.Locale

/**
 * Proof that a photo was taken with this app's GPS camera, with its time and place:
 * "AVIPSE|<ms>|<lat>|<lng>|<sig>", written into the photo (EXIF user comment) by the camera.
 * The office server checks the signature, so a photo from elsewhere, or edited times, are refused.
 */
object PhotoStamp {
    data class Info(val text: String, val time: Long, val lat: Double, val lng: Double)

    private fun sig(ms: String, lat: String, lng: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("AVIPSE|$ms|$lat|$lng|${Api.stampKey()}".toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    fun make(ms: Long, lat: Double, lng: Double): String {
        val la = String.format(Locale.US, "%.6f", lat); val ln = String.format(Locale.US, "%.6f", lng)
        return "AVIPSE|$ms|$la|$ln|" + sig(ms.toString(), la, ln)
    }

    fun parse(s: String?): Info? {
        val p = s?.trim()?.split("|") ?: return null
        if (p.size != 5 || p[0] != "AVIPSE") return null
        if (sig(p[1], p[2], p[3]) != p[4]) return null
        val ms = p[1].toLongOrNull() ?: return null
        return Info(s.trim(), ms, p[2].toDoubleOrNull() ?: return null, p[3].toDoubleOrNull() ?: return null)
    }

    /** The stamp in a photo file taken with the app camera, or null (gallery photo, no GPS, edited). */
    fun read(path: String): Info? = try {
        parse(ExifInterface(path).getAttribute(ExifInterface.TAG_USER_COMMENT))
    } catch (_: Exception) { null }
}
