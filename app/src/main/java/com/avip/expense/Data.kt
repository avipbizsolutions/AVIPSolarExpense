package com.avip.expense

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object Status {
    const val PENDING = "Pending"
    const val APPROVED = "Approved"
    const val REJECTED = "Rejected"
    const val REIMBURSED = "Reimbursed"
    /** Saved on the phone, not yet received by the office. */
    const val QUEUED = "Not sent"

    fun label(s: String) = when (s) {
        PENDING -> tr("Approval बाकी", "Pending approval")
        APPROVED -> "Approved"
        REJECTED -> "Rejected"
        REIMBURSED -> "Paid"
        QUEUED -> tr("भेजा जा रहा है…", "Sending…")
        else -> s
    }

    fun colors(s: String): Pair<Int, Int> = when (s) {
        PENDING -> Ui.SUN_LIGHT to Ui.SUN_TEXT
        APPROVED -> 0xFFE3EEF8.toInt() to Ui.BLUE
        REJECTED -> Ui.DANGER_LIGHT to Ui.DANGER
        REIMBURSED -> Ui.GREEN_LIGHT to Ui.GREEN
        else -> 0xFFECEFF3.toInt() to Ui.MUTED
    }
}

data class Claim(
    val id: String, val date: String, val billNo: String, val category: String, val description: String,
    val site: String, val district: String, val km: Double, val amount: Double, val paymentMode: String,
    val paidBy: String, val hasPhoto: Boolean, val status: String, val approvedBy: String, val approvedOn: String,
    val remarks: String, val reimbursedOn: String, val reimbursedVia: String, val paymentRef: String,
    val submittedOn: String, val member: String = "", val memberId: String = "", val team: String = "",
    val localPhoto: String = "",
    val odoStart: Double = 0.0, val odoEnd: Double = 0.0, val rate: Double = 0.0,
    val hasOdoStart: Boolean = false, val hasOdoEnd: Boolean = false,
    val localOdoStart: String = "", val localOdoEnd: String = "",
    val adminOnly: Boolean = false, val adminReason: String = "", val odoDatesDiffer: Boolean = false,
    val odoStartTime: String = "", val odoEndTime: String = "",
    val bike: String = "", val bikeNo: String = "",
) {
    val isFuel get() = Settings.isFuel(category)

    companion object {
        fun from(o: JSONObject) = Claim(
            o.optString("id"), o.optString("date"), o.optString("billNo"), o.optString("category"),
            o.optString("description"), o.optString("site"), o.optString("district"), o.optDouble("km", 0.0),
            o.optDouble("amount", 0.0), o.optString("paymentMode"), o.optString("paidBy"), o.optBoolean("hasPhoto"),
            o.optString("status"), o.optString("approvedBy"), o.optString("approvedOn"), o.optString("remarks"),
            o.optString("reimbursedOn"), o.optString("reimbursedVia"), o.optString("paymentRef"),
            o.optString("submittedOn"), o.optString("member"), o.optString("memberId"), o.optString("team"),
            o.optString("localPhoto"),
            o.optDouble("odoStart", 0.0), o.optDouble("odoEnd", 0.0), o.optDouble("rate", 0.0),
            o.optBoolean("hasOdoStart"), o.optBoolean("hasOdoEnd"),
            o.optString("localOdoStart"), o.optString("localOdoEnd"),
            o.optBoolean("adminOnly"), o.optString("adminReason"), o.optBoolean("odoDatesDiffer"),
            o.optString("odoStartTime"), o.optString("odoEndTime"),
            o.optString("bike"), o.optString("bikeNo"),
        )

        fun list(a: JSONArray?): List<Claim> = (0 until (a?.length() ?: 0)).map { from(a!!.getJSONObject(it)) }
    }
}

data class Advance(val id: String, val date: String, val member: String, val amount: Double, val via: String, val ref: String, val purpose: String, val givenBy: String) {
    companion object {
        fun list(a: JSONArray?): List<Advance> = (0 until (a?.length() ?: 0)).map {
            val o = a!!.getJSONObject(it)
            Advance(o.optString("id"), o.optString("date"), o.optString("member"), o.optDouble("amount", 0.0),
                o.optString("via"), o.optString("ref"), o.optString("purpose"), o.optString("givenBy"))
        }
    }
}

/** Money totals for one person, as worked out by the office server. */
data class Balance(
    val advanceGiven: Double, val advanceAdjusted: Double, val advanceBalance: Double, val advancePending: Double,
    val pendingApproval: Double, val toReimburse: Double, val reimbursed: Double,
    val name: String = "", val id: String = "", val team: String = "",
) {
    companion object {
        fun from(o: JSONObject?) = Balance(
            o?.optDouble("advanceGiven", 0.0) ?: 0.0, o?.optDouble("advanceAdjusted", 0.0) ?: 0.0,
            o?.optDouble("advanceBalance", 0.0) ?: 0.0, o?.optDouble("advancePending", 0.0) ?: 0.0,
            o?.optDouble("pendingApproval", 0.0) ?: 0.0, o?.optDouble("toReimburse", 0.0) ?: 0.0,
            o?.optDouble("reimbursed", 0.0) ?: 0.0,
            o?.optString("name") ?: "", o?.optString("id") ?: "", o?.optString("team") ?: "",
        )
    }
}

/** Numbers the office keeps in the "Settings" tab of the expense sheet. */
object Settings {
    const val DEFAULT_FUEL_RATE = 3.5
    const val DEFAULT_APPROVAL_LIMIT = 1000.0

    fun isFuel(category: String) = category.contains("fuel", true) || category.contains("petrol", true)

    private fun of(c: Context) = Store.load(c, "boot")?.optJSONObject("settings")
    fun fuelRate(c: Context) = of(c)?.optDouble("fuelRate", DEFAULT_FUEL_RATE)?.takeIf { it > 0 } ?: DEFAULT_FUEL_RATE
    fun approvalLimit(c: Context) = of(c)?.optDouble("approvalLimit", DEFAULT_APPROVAL_LIMIT)?.takeIf { it > 0 } ?: DEFAULT_APPROVAL_LIMIT
}

/** Lists and totals from the last "bootstrap", kept so the app opens without signal. */
object Store {
    private const val PREFS = "store"

    fun save(c: Context, key: String, o: JSONObject) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, o.toString()).apply()

    fun load(c: Context, key: String): JSONObject? {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "") ?: ""
        return if (s.isBlank()) null else try { JSONObject(s) } catch (_: Exception) { null }
    }

    fun clear(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    // Remember when a key was last fetched from the server, so returning to a screen
    // can show cached data instantly instead of re-fetching and rebuilding every time.
    private val lastFetch = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun markFetched(key: String) { lastFetch[key] = System.currentTimeMillis() }
    fun fetchedRecently(key: String, windowMs: Long = 15000): Boolean =
        System.currentTimeMillis() - (lastFetch[key] ?: 0L) < windowMs

    fun strings(o: JSONObject?, key: String, fallback: List<String>): List<String> {
        val a = o?.optJSONArray(key) ?: return fallback
        val l = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        return l.ifEmpty { fallback }
    }

    val DEFAULT_CATEGORIES = listOf(
        "Fuel / Petrol", "Travel Fare (Bus/Auto/Train)", "Food & Refreshment", "Lodging / Stay", "Site Labour",
        "Loading / Unloading", "Material Transport / Freight", "Small Material & Hardware", "Tools & Equipment",
        "Vehicle Repair & Maintenance", "Mobile / Internet Recharge", "DISCOM / Net Meter Fees",
        "Documents / Printing / Xerox", "Customer Meeting / Marketing", "Office Supplies", "Miscellaneous",
    )
    val DEFAULT_MODES = listOf("Cash", "UPI")
    val PAY_VIA = listOf("Cash", "UPI", "Bank Transfer")
}

object Fmt {
    private val day = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    /** "2026-10-30" → "30 Oct 2026" */
    fun date(v: String): String {
        if (v.length < 10) return v
        return try { day.format(iso.parse(v.substring(0, 10))!!) } catch (_: Exception) { v }
    }

    fun isoDay(ms: Long): String = iso.format(Date(ms))
    fun today(): String = isoDay(System.currentTimeMillis())
    fun parseIso(v: String): Long? = try { iso.parse(v.substring(0, 10))?.time } catch (_: Exception) { null }

    /** Indian grouping: ₹1,23,456 (paise shown only when there are any). */
    fun rupees(v: Double): String {
        val neg = v < 0
        val a = Math.abs(v)
        val whole = Math.floor(a).toLong()
        val paise = Math.round((a - whole) * 100)
        val s = whole.toString()
        val grouped = if (s.length <= 3) s else {
            val last3 = s.takeLast(3)
            var rest = s.dropLast(3)
            val parts = mutableListOf<String>()
            while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
            if (rest.isNotEmpty()) parts.add(0, rest)
            parts.joinToString(",") + "," + last3
        }
        return (if (neg) "-" else "") + "₹" + grouped + if (paise > 0) "." + paise.toString().padStart(2, '0') else ""
    }

    fun km(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)
}

/** Claim photos already on the phone (sent from here, or opened once), so they open without a download. */
object PhotoCache {
    private fun dir(c: Context) = File(c.filesDir, "photo_cache").apply { mkdirs() }
    fun file(c: Context, claimId: String, which: String) = File(dir(c), claimId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_" + which + ".jpg")
    fun has(c: Context, claimId: String, which: String) = file(c, claimId, which).let { it.exists() && it.length() > 0 }
    fun put(c: Context, claimId: String, which: String, src: File) { try { if (src.exists()) src.copyTo(file(c, claimId, which), overwrite = true) } catch (_: Exception) {} }
    fun put(c: Context, claimId: String, which: String, bytes: ByteArray) { try { file(c, claimId, which).writeBytes(bytes) } catch (_: Exception) {} }
    fun bitmap(c: Context, claimId: String, which: String): Bitmap? = try {
        BitmapFactory.decodeFile(file(c, claimId, which).absolutePath)
    } catch (_: Exception) { null }

    /** Keeps the cache from growing without end: drops the oldest photos beyond about 300. */
    fun trim(c: Context) {
        val files = dir(c).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(300).forEach { it.delete() }
    }
}

/** Decodes photos downsampled to the view size and keeps the decoded bitmaps in memory,
 *  so a photo opens instantly the second time and never decodes at full resolution. */
object Img {
    private val mem = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun get(key: String): Bitmap? = mem.get(key)
    fun put(key: String, b: Bitmap?) { if (b != null) mem.put(key, b) }

    private fun sample(w: Int, reqW: Int): Int {
        var s = 1
        if (reqW > 0) while (w / (s * 2) >= reqW) s *= 2
        return s
    }

    fun decodeFile(path: String, reqW: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        o.inSampleSize = sample(o.outWidth, reqW); o.inJustDecodeBounds = false
        BitmapFactory.decodeFile(path, o)
    } catch (_: Throwable) { null }

    fun decodeBytes(bytes: ByteArray, reqW: Int): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        o.inSampleSize = sample(o.outWidth, reqW); o.inJustDecodeBounds = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    } catch (_: Throwable) { null }
}

/** Bill photos: shrunk to a sensible size before they are sent. */
object Photos {
    fun dir(c: Context): File = File(c.filesDir, "bills").apply { mkdirs() }

    /** When a gallery photo was taken (from its EXIF), or null when the photo does not say. */
    fun takenAt(c: Context, uri: Uri): Long? = try {
        c.contentResolver.openInputStream(uri)?.use { input ->
            val ex = ExifInterface(input)
            val v = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            if (v.isNullOrBlank()) null else SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(v)?.time
        }
    } catch (_: Exception) { null }

    /** Copies a gallery / file-picker image into the app, upright and shrunk. Returns the file. */
    fun importUri(c: Context, uri: Uri, name: String): File? {
        return try {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val rotation = try {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) { 0 }
        val f = File(dir(c), "$name.jpg")
        writeShrunk(decode(bytes), rotation, f)
        f
        } catch (_: Exception) { null }
    }

    /** Reads a photo file and returns base64 JPEG, at most ~1600 px on the long side. */
    fun base64(path: String): String? {
        val f = File(path)
        if (!f.exists()) return null
        val out = File(f.parentFile, f.nameWithoutExtension + "_send.jpg")
        if (!out.exists()) writeShrunk(decode(f.readBytes()), 0, out)
        return Base64.encodeToString(out.readBytes(), Base64.NO_WRAP)
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Not an image")
    }

    private fun writeShrunk(src: Bitmap, rotation: Int, out: File) {
        val longSide = max(src.width, src.height)
        val scale = if (longSide > 1280) 1280f / longSide else 1f
        val m = Matrix().apply { postScale(scale, scale); if (rotation != 0) postRotate(rotation.toFloat()) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            out.writeBytes(bos.toByteArray())
        }
    }
}

/**
 * Bike journeys in progress: started with the start-odometer photo, kept on the phone as a
 * draft until the person adds the end-odometer photo and sends the claim.
 */
object Journeys {
    private const val PREFS = "journeys"

    fun all(c: Context): List<JSONObject> {
        val me = Session.user(c)?.id ?: return emptyList()
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.values
            .mapNotNull { v -> try { JSONObject(v as String) } catch (_: Exception) { null } }
            .filter { it.optString("owner") == me }
            .sortedBy { it.optLong("startTime") }
    }

    fun get(c: Context, id: String): JSONObject? = all(c).firstOrNull { it.optString("id") == id }

    fun save(c: Context, o: JSONObject) {
        o.put("owner", Session.user(c)?.id ?: "")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(o.getString("id"), o.toString()).commit()
    }

    fun remove(c: Context, id: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).commit()
    }
}

/**
 * Claims saved on the phone until the office server has them. Field teams often
 * have no signal; a claim is never lost, it is sent when the internet is back.
 */
object Outbox {
    private const val PREFS = "outbox"
    @Volatile private var running = false

    /** Claims of the person logged in now (another person's unsent claims stay until they log in). */
    fun all(c: Context): List<JSONObject> {
        val me = Session.user(c)?.id ?: return emptyList()
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.all.values.mapNotNull { v -> try { JSONObject(v as String) } catch (_: Exception) { null } }
            .filter { it.optString("owner") == me }
            .sortedBy { it.optString("savedAt") }
    }

    fun add(c: Context, claim: JSONObject, photoPath: String, odoStart: String = "", odoEnd: String = "") {
        val o = JSONObject().put("claim", claim).put("photo", photoPath).put("savedAt", System.currentTimeMillis().toString())
            .put("odoStartPhoto", odoStart).put("odoEndPhoto", odoEnd)
            .put("owner", Session.user(c)?.id ?: "")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(claim.getString("id"), o.toString()).commit()
        Sync.schedule(c)
    }

    fun remove(c: Context, id: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).commit()
    }

    /** Claims not yet sent, shaped like server claims, for the lists. */
    fun asClaims(c: Context): List<Claim> = all(c).map { o ->
        val cl = o.getJSONObject("claim")
        Claim.from(JSONObject(cl.toString()).put("status", Status.QUEUED).put("hasPhoto", o.optString("photo").isNotBlank())
            .put("localPhoto", o.optString("photo"))
            .put("localOdoStart", o.optString("odoStartPhoto")).put("localOdoEnd", o.optString("odoEndPhoto"))
            .put("hasOdoStart", o.optString("odoStartPhoto").isNotBlank()).put("hasOdoEnd", o.optString("odoEndPhoto").isNotBlank()))
    }

    /** Sends one claim now. Throws when it could not be sent. */
    fun send(c: Context, id: String): JSONObject {
        val o = all(c).firstOrNull { it.getJSONObject("claim").optString("id") == id }
            ?: throw ApiException("Claim not found on this phone.")
        val body = JSONObject().put("claim", o.getJSONObject("claim"))
        val photo = o.optString("photo")
        if (photo.isNotBlank()) {
            val data = Photos.base64(photo)
            if (data != null) body.put("photo", JSONObject().put("mime", "image/jpeg").put("data", data))
        }
        for ((key, field) in listOf("odoStartPhoto" to "odoStartPhoto", "odoEndPhoto" to "odoEndPhoto")) {
            val p = o.optString(key)
            if (p.isBlank()) continue
            val data = Photos.base64(p) ?: throw ApiException("Odometer photo is missing on this phone. Delete this claim and add it again.")
            body.put(field, JSONObject().put("mime", "image/jpeg").put("data", data))
        }
        val res = Api.call(c, "submitClaim", body, timeoutMs = 120000)
        // keep the sent copies on the phone, so the claim's photos open instantly later
        for ((key, which) in listOf("photo" to "bill", "odoStartPhoto" to "odoStart", "odoEndPhoto" to "odoEnd")) {
            val p = o.optString(key)
            if (p.isBlank()) continue
            val f = File(p)
            val sent = File(f.parentFile, f.nameWithoutExtension + "_send.jpg")
            PhotoCache.put(c, id, which, if (sent.exists()) sent else f)
        }
        remove(c, id)
        return res
    }

    /**
     * Sends everything waiting, one by one, on the calling (background) thread.
     * Returns how many were sent. Stops at the first network problem.
     */
    fun sendAll(c: Context): Int {
        if (Session.token(c).isBlank()) return 0
        val app = c.applicationContext
        var sent = 0
        synchronized(this) {
            for (o in all(app)) {
                // skip claims the office already refused; they wait for the person to fix or delete them
                if (o.optString("error").isNotBlank()) continue
                try {
                    send(app, o.getJSONObject("claim").getString("id")); sent++
                } catch (e: ApiException) {
                    if (e.sessionExpired || e.network) break
                    markError(app, o, e.message ?: "")
                } catch (e: Exception) {
                    markError(app, o, e.message ?: "Could not send")
                }
            }
        }
        return sent
    }

    /** Tries to send everything waiting, in the background. Safe to call often. */
    fun flush(c: Context, done: ((Int) -> Unit)? = null) {
        if (running || Session.token(c).isBlank()) return
        val app = c.applicationContext
        running = true
        Thread {
            var n = 0
            try { n = Sync.now(app) } finally { running = false }
            done?.invoke(n)
        }.start()
    }

    private fun markError(c: Context, o: JSONObject, msg: String) {
        o.put("error", msg)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(o.getJSONObject("claim").getString("id"), o.toString()).commit()
    }

    fun error(c: Context, id: String): String =
        all(c).firstOrNull { it.getJSONObject("claim").optString("id") == id }?.optString("error").orEmpty()
}
