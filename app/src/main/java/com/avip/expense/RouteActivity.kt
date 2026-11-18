package com.avip.expense

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The GPS route of one bike journey on a map, for approvers, accounts and founders
 * (the office sends the route to nobody else).
 */
class RouteActivity : AppCompatActivity() {

    companion object { const val EXTRA_ID = "id" }

    private lateinit var body: LinearLayout
    /** Nearby place name for each stoppage ("" when the phone could not look it up). */
    private var places: List<String> = emptyList()

    private fun stopsOf(o: JSONObject) = o.optJSONArray("stops")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    /** The phone's own address lookup; best effort (needs internet on most phones). */
    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lng: Double): String = try {
        val a = android.location.Geocoder(this, java.util.Locale("en", "IN")).getFromLocation(lat, lng, 1)?.firstOrNull()
        listOfNotNull(a?.subLocality ?: a?.thoroughfare ?: a?.featureName?.takeIf { f -> !f.any { it.isDigit() } }, a?.locality ?: a?.subAdminArea)
            .distinct().joinToString(", ")
    } catch (_: Exception) { "" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val u = Session.user(this)
        if (id.isBlank() || u == null || !u.seesTeam) { finish(); return }
        val claim = ClaimActivity.find(this, id)
        body = screen(tr("Journey का रास्ता", "Journey route"), (claim?.member?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: "") + (claim?.let { Fmt.date(it.date) } ?: id))
        body.addView(skeleton(2))
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val o = Api.call(this@RouteActivity, "claimRoute", JSONObject().put("claimId", id), timeoutMs = 60000)
                    val pts = o.optString("route").split(';').mapNotNull { p ->
                        val q = p.split(',')
                        val la = q.getOrNull(0)?.toDoubleOrNull(); val ln = q.getOrNull(1)?.toDoubleOrNull()
                        if (la == null || ln == null) null else la to ln
                    }
                    val w = resources.displayMetrics.widthPixels - dp(32)
                    val st = stopsOf(o)
                    places = st.map { placeName(it.optDouble("lat"), it.optDouble("lng")) }
                    Triple(o, pts, if (pts.size >= 2) MapTiles.route(pts, w, (w * 1.15f).toInt(), st.map { it.optDouble("lat") to it.optDouble("lng") }) else null)
                }
            }
            if (isFinishing) return@launch
            body.removeAllViews()
            r.onFailure { showError(it) }
            r.onSuccess { (o, pts, map) -> show(claim, o, pts, map) }
        }
    }

    private fun show(c: Claim?, o: JSONObject, pts: List<Pair<Double, Double>>, map: Bitmap?) {
        if (pts.size < 2 || map == null) {
            body.addView(note(tr("इस Journey का रास्ता record नहीं हुआ।", "No route was recorded for this journey."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
            return
        }
        body.addView(card(0) {
            clipToOutline = true
            addView(ImageView(context).apply { setImageBitmap(map); adjustViewBounds = true }, lp())
        }, lp().margins(this, b = 10))
        body.addView(text(tr("● हरा = शुरू   📍 लाल = आखिर   🟠 नंबर = रुकना   बैंगनी रेखा = GPS का रास्ता", "● Green = start photo   📍 Red = end photo   🟠 numbers = stoppages   Purple line = route by GPS"), 12.5f, Ui.MUTED), lp().margins(this, b = 10))
        body.addView(section(tr("हिसाब", "Summary")) {
            val straight = FloatArray(1).also { android.location.Location.distanceBetween(pts.first().first, pts.first().second, pts.last().first, pts.last().second, it) }[0] / 1000.0
            val onlyEnds = pts.size <= 2
            if (!onlyEnds) addView(kv(tr("GPS से नापी दूरी", "Distance by GPS"), Fmt.km(o.optDouble("km", 0.0)) + " km", bold = true))
            addView(kv(tr("शुरू से आखिर सीधी दूरी", "Start to end, straight line"), Fmt.km(Math.round(straight * 10) / 10.0) + " km", bold = onlyEnds))
            if (onlyEnds) addView(note(tr("सिर्फ़ दोनों odometer photos की जगह पता है। रास्ते में phone से GPS position नहीं मिली, इसलिए बीच का रास्ता नहीं है।",
                "Only the places of the two odometer photos are known. The phone gave no GPS positions on the way, so the road in between is not shown."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(context, t = 6, b = 6))
            if (c != null && c.km > 0) addView(kv(tr("Odometer की दूरी", "Distance by odometer"), Fmt.km(c.km) + " km"))
            if (o.optString("start").isNotBlank()) addView(kv(tr("शुरू", "Started"), o.optString("start")))
            if (o.optString("end").isNotBlank()) addView(kv(tr("आखिर", "Ended"), o.optString("end")))
            addView(kv(tr("GPS points", "GPS points"), pts.size.toString()))
            val st = stopsOf(o)
            addView(kv(tr("रुकना (3 min+)", "Stoppages (3 min+)"), if (st.isEmpty()) tr("कोई नहीं", "none")
                else tr("${st.size} बार · कुल ${Fmt.minutes(st.sumOf { it.optInt("min") })}", "${st.size} · total ${Fmt.minutes(st.sumOf { it.optInt("min") })}"), bold = st.isNotEmpty()))
        })
        // ---- each stoppage: when, how long, where (tap to see it in Google Maps)
        val st = stopsOf(o)
        if (st.isNotEmpty()) body.addView(section(tr("कहाँ कितनी देर रुके", "Where and how long it stopped")) {
            st.forEachIndexed { i, s ->
                addView(horizontal {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    foreground = context.ripple()
                    setOnClickListener {
                        val q = "${s.optDouble("lat")},${s.optDouble("lng")}"
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q"))) } catch (_: Exception) {}
                    }
                    addView(text((i + 1).toString(), 14f, android.graphics.Color.WHITE, bold = true) {
                        gravity = Gravity.CENTER
                        background = rounded(0xFFE67814.toInt(), dpf(14f))
                    }, lp(dp(28), dp(28)).margins(context, r = 12))
                    addView(vertical {
                        addView(text(s.optString("from") + " – " + s.optString("to"), 15f, Ui.TEXT, bold = true))
                        val place = places.getOrNull(i).orEmpty()
                        addView(text(place.ifBlank { String.format(java.util.Locale.US, "%.5f, %.5f", s.optDouble("lat"), s.optDouble("lng")) } + "  ›", 12.5f, Ui.MUTED) { maxLines = 2 })
                    }, lp(0, WRAP, 1f))
                    addView(text(Fmt.minutes(s.optInt("min")), 15f, if (s.optInt("min") >= 30) Ui.DANGER else Ui.TEXT, bold = true), lp(WRAP, WRAP).margins(context, l = 8))
                }, lp())
            }
            addView(text(tr("एक जगह 3 मिनट या ज़्यादा रुकने को गिना गया है। Phone का GPS बीच में बंद हो तो समय कम-ज़्यादा हो सकता है।",
                "A halt of 3 minutes or more in one place is counted. If the phone's GPS was off for a while, the times can be a little off."), 12f, Ui.MUTED), lp().margins(context, t = 6))
        }, lp().margins(this, t = 10))
        body.addView(pillButton(tr("Google Maps में खोलें", "Open in Google Maps"), Ui.BRAND, iconRes = R.drawable.ic_location) {
            // start, end and up to 8 points in between, so Google Maps shows about the same road
            val mid = if (pts.size > 2) (1..8).map { pts[(it * (pts.size - 1)) / 9] }.distinct().filter { it != pts.first() && it != pts.last() } else emptyList()
            val f = { p: Pair<Double, Double> -> "${p.first},${p.second}" }
            val url = "https://www.google.com/maps/dir/?api=1&travelmode=driving&origin=" + f(pts.first()) + "&destination=" + f(pts.last()) +
                (if (mid.isNotEmpty()) "&waypoints=" + Uri.encode(mid.joinToString("|") { f(it) }) else "")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast(tr("Maps नहीं खुल सका", "Could not open Maps")) }
        }, lp().margins(this, t = 12))
        body.addView(text(tr("Phone का GPS या app बीच में बंद हुआ हो तो रास्ता अधूरा हो सकता है।", "The route can be incomplete if the phone's GPS or the app was stopped on the way."), 12f, Ui.MUTED), lp().margins(this, t = 10, b = 20))
    }
}
