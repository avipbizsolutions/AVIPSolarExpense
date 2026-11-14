package com.avip.expense

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One claim: details, bill photo, and the next step for this person's role. */
class ClaimActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"

        /** Finds a claim in what the phone already has: not-yet-sent, own claims, team claims. */
        fun find(a: android.content.Context, id: String): Claim? =
            Outbox.asClaims(a).firstOrNull { it.id == id }
                ?: Claim.list(Store.load(a, "mine")?.optJSONArray("claims")).firstOrNull { it.id == id }
                ?: Claim.list(Store.load(a, "team")?.optJSONArray("claims")).firstOrNull { it.id == id }
    }

    private lateinit var claim: Claim
    private lateinit var body: LinearLayout
    private var photoView: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        claim = find(this, intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run { finish(); return }
        render()
    }

    private fun render() {
        val u = Session.user(this) ?: return
        val c = claim
        val mine = c.memberId.isBlank() && c.member.isBlank() || c.memberId == u.id
        val actions = vertical { }
        body = screen(c.category, Fmt.date(c.date) + " · " + c.id, bottom = actions)

        body.addView(card(18) {
            addView(horizontal {
                addView(vertical {
                    if (c.member.isNotBlank()) addView(text(c.member + if (c.team.isNotBlank()) " · " + c.team else "", 14f, Ui.MUTED, bold = true))
                    addView(text(Fmt.rupees(c.amount), 30f, Ui.TEXT, bold = true))
                }, lp(0, WRAP, 1f))
                addView(statusPill(c.status))
            })
            addView(text(c.description, 15f, Ui.TEXT), lp().margins(context, t = 6))
        }, lp().margins(this, b = 12))

        if (c.status == Status.QUEUED) {
            val err = Outbox.error(this, c.id)
            body.addView(note(if (err.isNotBlank()) tr("Office ने यह claim नहीं लिया: $err", "The office did not accept this claim: $err") else tr("यह claim अभी इस phone में है। Internet आते ही अपने आप चला जाएगा।", "This claim is still on this phone. It will be sent automatically when the internet is back."),
                if (err.isNotBlank()) Ui.DANGER_LIGHT else Ui.SUN_LIGHT, if (err.isNotBlank()) Ui.DANGER else Ui.SUN_TEXT))
        }
        if (c.status == Status.REJECTED) body.addView(note(tr("Reject का कारण: ", "Reason for rejection: ") + c.remarks.ifBlank { "—" } + "\n" + c.approvedBy + " · " + Fmt.date(c.approvedOn), Ui.DANGER_LIGHT, Ui.DANGER))

        body.addView(section(tr("जानकारी", "Details")) {
            addView(kv(tr("तारीख", "Date"), Fmt.date(c.date)))
            addView(kv(tr("किस चीज़ का खर्च", "Category"), c.category))
            addView(kv(tr("पैसे किसने दिए", "Who paid"), when (c.paidBy) { "Advance" -> tr("Advance से", "From advance"); "Company" -> tr("Company ने दिया", "Paid by company"); else -> tr("अपने पैसे से", "Own money") }))
            if (c.paymentMode.isNotBlank()) addView(kv(tr("Payment कैसे", "Paid via"), c.paymentMode))
            if (c.billNo.isNotBlank()) addView(kv(tr("Bill नंबर", "Bill No."), c.billNo))
            if (c.site.isNotBlank()) addView(kv("Customer / Site", c.site))
            if (c.district.isNotBlank()) addView(kv(tr("जगह", "Location"), c.district))
            if (c.km > 0) addView(kv("KM", Fmt.km(c.km)))
            if (c.isFuel && c.odoEnd > 0) {
                addView(kv(tr("Odometer", "Odometer"), Fmt.km(c.odoStart) + " → " + Fmt.km(c.odoEnd)))
                if (c.bike == "Company") addView(kv("Bike", tr("Company की bike", "Company bike") + if (c.bikeNo.isNotBlank()) " · " + c.bikeNo else ""))
                else addView(kv("Bike", tr("अपनी bike", "Own bike")))
                val rate = if (c.rate > 0) c.rate else Settings.fuelRate(this@ClaimActivity)
                if (c.odoStartTime.isNotBlank() || c.odoEndTime.isNotBlank())
                    addView(kv(tr("Journey का समय", "Journey time"), journeyTimes(c.odoStartTime, c.odoEndTime)))
                // GPS distance: the office sends it only to approvers, accounts and founders
                if (u.seesTeam && c.status != Status.QUEUED && c.trackKm >= 0) {
                    val diff = c.km - c.trackKm
                    addView(kv(tr("GPS से नापी दूरी", "Distance by GPS"), "${Fmt.km(c.trackKm)} km" + tr(" · odometer ${Fmt.km(c.km)} km", " · odometer ${Fmt.km(c.km)} km"), bold = true))
                    if (c.km > 0 && diff > maxOf(3.0, c.trackKm * 0.2)) addView(text(tr("⚠ Odometer की दूरी GPS से ${Fmt.km(Math.round(diff * 10) / 10.0)} km ज़्यादा है।", "⚠ The odometer distance is ${Fmt.km(Math.round(diff * 10) / 10.0)} km more than GPS measured."), 13f, Ui.DANGER, bold = true), lp().margins(context, t = 4))
                    if (c.trackKm == 0.0) addView(text(tr("GPS चालू था, पर phone कहीं गया नहीं।", "GPS was on, but the phone did not move."), 13f, Ui.DANGER, bold = true), lp().margins(context, t = 4))
                } else if (u.seesTeam && c.status != Status.QUEUED && c.viaJourney) addView(kv(tr("GPS से नापी दूरी", "Distance by GPS"), tr("नहीं मिली (GPS / app बंद)", "not recorded (GPS or app was off)")))
                if (u.seesTeam && c.hasRoute) addView(pillButton(tr("रास्ते का Map देखें", "View route map"), Ui.BRAND_LIGHT, Ui.BRAND_DARK, heightDp = 44, iconRes = R.drawable.ic_location) {
                    startActivity(android.content.Intent(this@ClaimActivity, RouteActivity::class.java).putExtra(RouteActivity.EXTRA_ID, c.id))
                }, lp().margins(context, t = 8, b = 4))
                if (!c.viaJourney && c.status != Status.QUEUED) addView(text(tr("⚠ यह fuel claim \"Journey शुरू करें\" से नहीं बना — Management approval", "⚠ Not made through \"Start journey\": management approval"), 13f, Ui.SUN_TEXT, bold = true), lp().margins(context, t = 4))
                if (c.odoGap != 0.0) addView(note(
                    (if (c.odoGap > 0) tr("⚠ Odometer मेल नहीं खाता: इस bike की पिछली journey ${Fmt.km(c.odoPrevEnd)} पर खत्म हुई थी, यह ${Fmt.km(c.odoStart)} से शुरू है — बीच के ${Fmt.km(c.odoGap)} km का claim नहीं है।",
                        "⚠ Odometer does not continue: this bike's last journey ended at ${Fmt.km(c.odoPrevEnd)}, this one starts at ${Fmt.km(c.odoStart)} — ${Fmt.km(c.odoGap)} km in between are not claimed.")
                    else tr("⚠ Odometer मेल नहीं खाता: इस bike की पिछली journey ${Fmt.km(c.odoPrevEnd)} पर खत्म हुई थी, पर यह ${Fmt.km(c.odoStart)} से शुरू है (${Fmt.km(-c.odoGap)} km कम)।",
                        "⚠ Odometer does not continue: this bike's last journey ended at ${Fmt.km(c.odoPrevEnd)}, but this one starts at ${Fmt.km(c.odoStart)} (${Fmt.km(-c.odoGap)} km lower).")) +
                    (if (c.odoPrevBy.isNotBlank()) tr("\nपिछली journey: ", "\nLast journey: ") + c.odoPrevBy + (if (c.odoPrevDate.isNotBlank()) " · " + Fmt.date(c.odoPrevDate) else "") else ""),
                    Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(context, t = 6))
                if (c.odoDatesDiffer) addView(text(tr("⚠ Photos अलग-अलग तारीख की (या तारीख नहीं) — Management approval", "⚠ Photos on different dates (or no date): management approval"), 13f, Ui.DANGER, bold = true), lp().margins(context, t = 4))
                if (c.bike == "Company") addView(kv(tr("हिसाब", "Worked out"), tr("Petrol bill ", "Petrol bill ") + Fmt.rupees(c.amount) + " · " + Fmt.km(c.km) + " km · ₹" +
                    String.format(java.util.Locale.US, "%.2f", if (c.km > 0) c.amount / c.km else 0.0) + "/km", bold = true))
                else addView(kv(tr("हिसाब", "Worked out"), "${Fmt.km(c.km)} km × ₹${Fmt.km(rate)} = ${Fmt.rupees(c.amount)}", bold = true))
            }
            if (c.submittedOn.isNotBlank()) addView(kv(tr("भेजा", "Sent"), c.submittedOn))
        })

        if (c.approvedBy.isNotBlank() || c.status == Status.REIMBURSED) body.addView(section(tr("Approval और Payment", "Approval and payment")) {
            if (c.approvedBy.isNotBlank()) addView(kv(if (c.status == Status.REJECTED) tr("Reject किया", "Rejected by") else tr("Approve किया", "Approved by"), c.approvedBy))
            if (c.approvedOn.isNotBlank()) addView(kv(tr("कब", "When"), c.approvedOn))
            if (c.remarks.isNotBlank() && c.status != Status.REJECTED) addView(kv(tr("टिप्पणी", "Note"), c.remarks))
            if (c.status == Status.REIMBURSED) {
                addView(kv(tr("Payment की तारीख", "Paid on"), Fmt.date(c.reimbursedOn), bold = true))
                addView(kv(tr("Payment कैसे", "Paid via"), c.reimbursedVia))
                if (c.paymentRef.isNotBlank()) addView(kv(tr("Reference नंबर", "Reference"), c.paymentRef))
            }
        })

        if (c.isFuel && (c.hasOdoStart || c.hasOdoEnd)) body.addView(section(tr("Odometer photos", "Odometer photos")) {
            for ((which, label, local, has) in listOf(
                Quad("odoStart", tr("शुरू का Odometer", "Start odometer"), c.localOdoStart, c.hasOdoStart),
                Quad("odoEnd", tr("आखिर का Odometer", "End odometer"), c.localOdoEnd, c.hasOdoEnd),
            )) {
                if (!has) continue
                addView(text(label, 14f, Ui.MUTED, bold = true), lp().margins(context, t = 8, b = 6))
                val box = vertical { }
                addView(box, lp())
                if (local.isNotBlank() && File(local).exists()) showPhotoAsync(box, "local:$local") { Img.decodeFile(local, screenW()) }
                else if (PhotoCache.has(this@ClaimActivity, c.id, which)) showPhotoAsync(box, "${c.id}:$which") { Img.decodeFile(PhotoCache.file(this@ClaimActivity, c.id, which).absolutePath, screenW()) }
                else box.addView(pillButton(tr("$label देखें", "View $label".lowercase().replaceFirstChar { it.uppercase() }), Ui.BRAND_LIGHT, Ui.TEXT, heightDp = 44, iconRes = R.drawable.ic_camera) { loadPhoto(which, box) }, lp())
                val reading = if (which == "odoStart") c.odoStart else c.odoEnd
                if (reading > 0) addView(text(tr("भरा हुआ reading: ", "Reading entered: ") + Fmt.km(reading) + " km", 14f, Ui.TEXT, bold = true), lp().margins(context, t = 6, b = 2))
            }
            if (c.odoStart > 0 && c.odoEnd > 0) addView(text(tr("कुल दूरी: ", "Distance: ") + Fmt.km(c.odoEnd - c.odoStart) + " km", 14.5f, Ui.BRAND_DARK, bold = true), lp().margins(context, t = 10))
        })

        body.addView(section(tr("Bill photos", "Bill photos")) {
            if (c.localPhoto.isNotBlank() && File(c.localPhoto).exists()) {
                val v = vertical { }; addView(v, lp())
                showPhotoAsync(v, "local:${c.localPhoto}") { Img.decodeFile(c.localPhoto, screenW()) }
            } else if (c.hasPhoto) {
                val count = if (c.billCount > 0) c.billCount else 1
                for (i in 0 until count) {
                    if (count > 1) addView(text(tr("Bill ${i + 1}", "Bill ${i + 1}"), 13f, Ui.MUTED, bold = true), lp().margins(context, t = if (i == 0) 0 else 10, b = 4))
                    val v = vertical { }; addView(v, lp())
                    val ck = "bill$i"
                    if (PhotoCache.has(this@ClaimActivity, c.id, ck)) showPhotoAsync(v, "${c.id}:$ck") { Img.decodeFile(PhotoCache.file(this@ClaimActivity, c.id, ck).absolutePath, screenW()) }
                    else { val idx = i; v.addView(pillButton(tr("Bill photo देखें", "View bill photo") + (if (count > 1) " ${i + 1}" else ""), Ui.BRAND_LIGHT, Ui.TEXT, heightDp = 46, iconRes = R.drawable.ic_camera) { loadPhoto("bill", v, idx, ck) }, lp()) }
                }
            } else addView(note(tr("इस claim के साथ bill photo नहीं है।", "This claim has no bill photo."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
        })

        // ---- next step for this person
        when {
            c.status == Status.QUEUED -> {
                actions.addView(pillButton(tr("अभी भेजें", "Send now"), Ui.SUN, Ui.TEXT) {
                    busy(tr("भेजा जा रहा है…", "Sending…"), { Outbox.send(this, c.id) }) { toast(tr("Claim भेज दिया", "Claim sent")); finish() }
                }, lp())
                actions.addView(text(tr("Phone से हटाएँ", "Delete from phone"), 14f, Ui.DANGER, bold = true) {
                    gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4))
                    setOnClickListener { confirm(tr("Claim हटाएँ?", "Delete claim?"), tr("यह claim office नहीं गया है। Phone से हटा दें?", "This claim has not reached the office. Delete it from the phone?")) { Outbox.remove(this@ClaimActivity, c.id); finish() } }
                }, lp())
            }
            c.status == Status.PENDING && u.canApprove && !mine && !u.canDecide(c, Settings.approvalLimit(this)) -> {
                val limit = Settings.approvalLimit(this)
                actions.addView(note(if (c.isFuel && !c.viaJourney && !c.odoDatesDiffer && c.amount <= limit) tr("यह fuel claim \"Journey शुरू करें\" से नहीं बना — सिर्फ Atul या Rahul approve कर सकते हैं।",
                    "This fuel claim was not made through \"Start journey\": only Atul or Rahul can approve it.") else if (c.odoDatesDiffer && c.amount <= limit) tr("Odometer की photos अलग-अलग तारीख की हैं — सिर्फ Atul या Rahul approve कर सकते हैं।",
                    "The odometer photos are on different dates: only Atul or Rahul can approve this claim.") else if (c.amount > limit) tr("${Fmt.rupees(limit)} से ऊपर का claim — सिर्फ Atul या Rahul approve कर सकते हैं।",
                    "Above ${Fmt.rupees(limit)}: only Atul or Rahul can approve this claim.")
                    else tr("Manager का claim — सिर्फ Atul या Rahul approve कर सकते हैं।", "Claim of a manager: only Atul or Rahul can approve it."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp())
            }
            c.status == Status.PENDING && u.canApprove && !mine -> {
                actions.addView(horizontal {
                    addView(pillButton(tr("Reject करें", "Reject"), Ui.DANGER_LIGHT, Ui.DANGER) { reject() }, lp(0, WRAP, 1f).margins(context, r = 8))
                    addView(pillButton(tr("Approve करें", "Approve"), Ui.GREEN, iconRes = R.drawable.ic_check) { approve() }, lp(0, WRAP, 1.4f))
                }, lp())
            }
            c.status == Status.APPROVED && c.paidBy == "Self" && u.isAccounts -> {
                actions.addView(pillButton(tr("Paid mark करें", "Mark as paid"), Ui.GREEN, iconRes = R.drawable.ic_rupee) {
                    payDialog(listOf(c.id), c.amount) { finish() }
                }, lp())
            }
            c.status == Status.REJECTED && mine -> {
                actions.addView(pillButton(tr("ठीक करके फिर भेजें", "Fix and resend"), Ui.SUN, Ui.TEXT, iconRes = R.drawable.ic_edit) {
                    startActivity(android.content.Intent(this@ClaimActivity, ClaimFormActivity::class.java).putExtra(ClaimFormActivity.EXTRA_FIX, c.id))
                    finish()
                }, lp())
            }
            c.status == Status.PENDING && mine -> {
                actions.addView(text(tr("यह claim हटाएँ", "Delete this claim"), 14f, Ui.DANGER, bold = true) {
                    gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener {
                        confirm(tr("Claim हटाएँ?", "Delete claim?"), tr("Approval से पहले आप claim हटा सकते हैं। हटाएँ?", "You can delete a claim before it is approved. Delete it?")) {
                            busy(tr("हटाया जा रहा है…", "Deleting…"), { Api.call(this@ClaimActivity, "deleteClaim", JSONObject().put("claimId", c.id)) }) { toast(tr("Claim हटा दिया", "Claim deleted")); finish() }
                        }
                    }
                }, lp())
            }
            else -> (actions.parent as? View)?.visibility = View.GONE
        }
    }

    private fun approve() {
        sendOrQueue(tr("Approve हो रहा है…", "Approving…"), "decide", JSONObject().put("claimId", claim.id).put("approve", true),
            "Approve · " + claim.member + " · " + Fmt.rupees(claim.amount), listOf(claim.id)) { r ->
            skippedOr(r) { if (!r.optBoolean("queued")) toast(tr("Approve हो गया", "Approved")); finish() }
        }
    }

    private fun reject() {
        askText(tr("Reject का कारण", "Reason for rejection"), tr("जैसे: Bill साफ नहीं है", "e.g. Bill is not clear")) { note ->
            sendOrQueue(tr("Reject हो रहा है…", "Rejecting…"), "decide", JSONObject().put("claimId", claim.id).put("approve", false).put("note", note),
                "Reject · " + claim.member + " · " + Fmt.rupees(claim.amount), listOf(claim.id)) { r ->
                skippedOr(r) { if (!r.optBoolean("queued")) toast(tr("Reject हो गया", "Rejected")); finish() }
            }
        }
    }

    private fun screenW() = resources.displayMetrics.widthPixels

    /** "10:05 AM → 11:40 AM · 1 h 35 min" from the two GPS-photo times ("yyyy-MM-dd HH:mm"). */
    private fun journeyTimes(start: String, end: String): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val show = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.ENGLISH)
        val a = try { f.parse(start) } catch (_: Exception) { null }
        val b = try { f.parse(end) } catch (_: Exception) { null }
        val base = (a?.let { show.format(it) } ?: "?") + " → " + (b?.let { show.format(it) } ?: "?")
        if (a == null || b == null || b.time <= a.time) return base
        val mins = (b.time - a.time) / 60000
        return base + " · " + (if (mins >= 60) "${mins / 60} h " else "") + "${mins % 60} min"
    }

    private fun loadPhoto(which: String, box: LinearLayout, index: Int = 0, cacheWhich: String = which) {
        busy(tr("Photo आ रही है…", "Loading photo…"), { Api.call(this, "billPhoto", JSONObject().put("claimId", claim.id).put("which", which).put("index", index), timeoutMs = 90000) }) { r ->
            val bytes = Base64.decode(r.optString("data"), Base64.DEFAULT)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    PhotoCache.put(this@ClaimActivity, claim.id, cacheWhich, bytes)
                    Img.decodeBytes(bytes, screenW())
                }
                Img.put("${claim.id}:$cacheWhich", bmp)
                if (!isFinishing) showImage(bmp, box)
            }
        }
    }

    /** Shows a cached bitmap at once, otherwise decodes it off the main thread and caches it. */
    private fun showPhotoAsync(box: LinearLayout, key: String, decode: () -> android.graphics.Bitmap?) {
        Img.get(key)?.let { showImage(it, box); return }
        box.removeAllViews()
        box.addView(text(tr("Photo आ रही है…", "Loading photo…"), 13f, Ui.MUTED), lp())
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decode() }
            Img.put(key, bmp)
            if (!isFinishing) showImage(bmp, box)
        }
    }

    private data class Quad(val which: String, val label: String, val local: String, val has: Boolean)

    private fun showImage(bmp: android.graphics.Bitmap?, target: LinearLayout? = photoView) {
        val box = target ?: return
        box.removeAllViews()
        if (bmp == null) { box.addView(note(tr("Photo नहीं खुल सकी।", "Could not open the photo."))); return }
        box.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                AlertDialog.Builder(this@ClaimActivity).setView(ImageView(this@ClaimActivity).apply {
                    setImageBitmap(bmp); adjustViewBounds = true
                }).setPositiveButton(tr("बंद करें", "Close"), null).show()
            }
        }, lp(MATCH, WRAP))
    }
}

/** Shows what the server skipped (e.g. own claim, already approved), else runs [ok]. */
fun AppCompatActivity.skippedOr(r: JSONObject, ok: () -> Unit) {
    val skipped: JSONArray? = r.optJSONArray("skipped")
    if (skipped != null && skipped.length() > 0 && r.optInt("done") == 0) {
        AlertDialog.Builder(this).setTitle(tr("नहीं हो सका", "Could not complete"))
            .setMessage((0 until skipped.length()).joinToString("\n") { skipped.optString(it) })
            .setPositiveButton("OK", null).show()
        return
    }
    if (skipped != null && skipped.length() > 0) toast(tr("${skipped.length()} claim छोड़े गए", "${skipped.length()} claims skipped"))
    ok()
}

/** Accounts: mark approved claims as paid (Cash / UPI / Bank Transfer, reference if any). */
fun AppCompatActivity.payDialog(ids: List<String>, total: Double, who: String = "", onDone: () -> Unit) {
    var via = ""
    var date = Fmt.today()
    val ref = input(tr("UPI / Bank reference (अगर हो)", "UPI / bank reference (if any)"))
    val dateBtn = text(tr("तारीख: ", "Date: ") + Fmt.date(date), 15f, Ui.BLUE, bold = true) { setPadding(0, dp(12), 0, dp(4)) }
    val box = vertical(18) {
        if (who.isNotBlank()) addView(text(who, 18f, Ui.TEXT, bold = true), lp().margins(context, b = 2))
        addView(text(tr("${ids.size} claim · कुल ${Fmt.rupees(total)}", "${ids.size} claims · total ${Fmt.rupees(total)}"), 16f, Ui.TEXT, bold = true))
        if (who.isNotBlank() && ids.size > 1) addView(text(tr("एक payment, एक reference — सारे claims Paid mark होंगे।", "One payment, one reference: all these claims are marked paid."), 12.5f, Ui.MUTED), lp().margins(context, t = 2))
        addView(text(req(tr("Payment कैसे किया", "How was it paid")), 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
        addView(chips(Store.PAY_VIA, "") { via = it }, lp())
        addView(ref, lp().margins(context, t = 12))
        addView(dateBtn, lp())
    }
    dateBtn.setOnClickListener { pickDate(date) { d -> date = d; dateBtn.text = tr("तारीख: ", "Date: ") + Fmt.date(d) } }
    val d = AlertDialog.Builder(this).setTitle(tr("Paid mark करें", "Mark as paid")).setView(box)
        .setPositiveButton(tr("Paid करें", "Paid"), null).setNegativeButton(tr("रद्द करें", "Cancel"), null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (via.isBlank()) { toast(tr("Cash / UPI / Bank Transfer चुनें", "Choose Cash, UPI or Bank Transfer")); return@setOnClickListener }
            d.dismiss()
            sendOrQueue(tr("Save हो रहा है…", "Saving…"), "markPaid", JSONObject().put("claimIds", JSONArray(ids)).put("via", via)
                .put("ref", ref.text.toString().trim()).put("date", date), "Paid · " + (if (who.isNotBlank()) "$who · " else "") + "${ids.size} claim · ${Fmt.rupees(total)}", ids) { r ->
                skippedOr(r) { if (!r.optBoolean("queued")) toast(tr("${r.optInt("done")} claim Paid · ${Fmt.rupees(r.optDouble("total"))}", "${r.optInt("done")} claim paid · ${Fmt.rupees(r.optDouble("total"))}")); onDone() }
            }
        }
    }
    d.show()
}
