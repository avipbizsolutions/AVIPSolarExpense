package com.avip.expense

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
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
                    Triple(o, pts, if (pts.size >= 2) MapTiles.route(pts, w, (w * 1.15f).toInt()) else null)
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
        body.addView(text(tr("● हरा = शुरू   📍 लाल = आखिर   बैंगनी रेखा = GPS का रास्ता", "● Green = start   📍 Red = end   Purple line = route by GPS"), 12.5f, Ui.MUTED), lp().margins(this, b = 10))
        body.addView(section(tr("हिसाब", "Summary")) {
            addView(kv(tr("GPS से नापी दूरी", "Distance by GPS"), Fmt.km(o.optDouble("km", 0.0)) + " km", bold = true))
            if (c != null && c.km > 0) addView(kv(tr("Odometer की दूरी", "Distance by odometer"), Fmt.km(c.km) + " km"))
            if (o.optString("start").isNotBlank()) addView(kv(tr("शुरू", "Started"), o.optString("start")))
            if (o.optString("end").isNotBlank()) addView(kv(tr("आखिर", "Ended"), o.optString("end")))
            addView(kv(tr("GPS points", "GPS points"), pts.size.toString()))
        })
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
