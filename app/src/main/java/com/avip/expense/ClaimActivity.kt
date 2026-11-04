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

        body.addView(section("Details") {
            addView(kv(tr("तारीख", "Date"), Fmt.date(c.date)))
            addView(kv("Category", c.category))
            addView(kv(tr("पैसे किसने दिए", "Who paid"), when (c.paidBy) { "Advance" -> tr("Advance से", "From advance"); "Company" -> tr("Company ने दिया", "Paid by company"); else -> tr("अपने पैसे से", "Own money") }))
            if (c.paymentMode.isNotBlank()) addView(kv(tr("Payment कैसे", "Paid via"), c.paymentMode))
            if (c.billNo.isNotBlank()) addView(kv("Bill No.", c.billNo))
            if (c.site.isNotBlank()) addView(kv("Customer / Site", c.site))
            if (c.district.isNotBlank()) addView(kv(tr("जगह", "Location"), c.district))
            if (c.km > 0) addView(kv("KM", Fmt.km(c.km)))
            if (c.isFuel && c.odoEnd > 0) {
                addView(kv(tr("Odometer", "Odometer"), Fmt.km(c.odoStart) + " → " + Fmt.km(c.odoEnd)))
                val rate = if (c.rate > 0) c.rate else Settings.fuelRate(this@ClaimActivity)
                addView(kv(tr("हिसाब", "Worked out"), "${Fmt.km(c.km)} km × ₹${Fmt.km(rate)} = ${Fmt.rupees(c.amount)}", bold = true))
            }
            if (c.submittedOn.isNotBlank()) addView(kv(tr("भेजा", "Sent"), c.submittedOn))
        })

        if (c.approvedBy.isNotBlank() || c.status == Status.REIMBURSED) body.addView(section(tr("Approval और Payment", "Approval and payment")) {
            if (c.approvedBy.isNotBlank()) addView(kv(if (c.status == Status.REJECTED) tr("Reject किया", "Rejected by") else tr("Approve किया", "Approved by"), c.approvedBy))
            if (c.approvedOn.isNotBlank()) addView(kv(tr("कब", "When"), c.approvedOn))
            if (c.remarks.isNotBlank() && c.status != Status.REJECTED) addView(kv("Note", c.remarks))
            if (c.status == Status.REIMBURSED) {
                addView(kv("Paid on", Fmt.date(c.reimbursedOn), bold = true))
                addView(kv("Paid via", c.reimbursedVia))
                if (c.paymentRef.isNotBlank()) addView(kv("Reference", c.paymentRef))
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
                if (local.isNotBlank() && File(local).exists()) showImage(BitmapFactory.decodeFile(local, BitmapFactory.Options().apply { inSampleSize = 2 }), box)
                else box.addView(pillButton(tr("$label देखें", "View $label".lowercase().replaceFirstChar { it.uppercase() }), Ui.BRAND_LIGHT, Ui.TEXT, heightDp = 44, iconRes = R.drawable.ic_camera) { loadPhoto(which, box) }, lp())
            }
        })

        body.addView(section("Bill photo") {
            photoView = vertical { }
            addView(photoView, lp())
            when {
                c.localPhoto.isNotBlank() && File(c.localPhoto).exists() -> showImage(BitmapFactory.decodeFile(c.localPhoto, BitmapFactory.Options().apply { inSampleSize = 2 }))
                c.hasPhoto -> photoView?.addView(pillButton(tr("Bill photo देखें", "View bill photo"), Ui.BRAND_LIGHT, Ui.TEXT, heightDp = 46, iconRes = R.drawable.ic_camera) { loadPhoto("bill", photoView!!) }, lp())
                else -> photoView?.addView(note(tr("इस claim के साथ bill photo नहीं है।", "This claim has no bill photo."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
            }
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
                actions.addView(note(if (c.amount > limit) tr("${Fmt.rupees(limit)} से ऊपर का claim — सिर्फ Atul या Rahul approve कर सकते हैं।",
                    "Above ${Fmt.rupees(limit)}: only Atul or Rahul can approve this claim.")
                    else tr("Manager का claim — सिर्फ Atul या Rahul approve कर सकते हैं।", "Claim of a manager: only Atul or Rahul can approve it."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp())
            }
            c.status == Status.PENDING && u.canApprove && !mine -> {
                actions.addView(horizontal {
                    addView(pillButton("Reject", Ui.DANGER_LIGHT, Ui.DANGER) { reject() }, lp(0, WRAP, 1f).margins(context, r = 8))
                    addView(pillButton("Approve", Ui.GREEN, iconRes = R.drawable.ic_check) { approve() }, lp(0, WRAP, 1.4f))
                }, lp())
            }
            c.status == Status.APPROVED && c.paidBy == "Self" && u.isAccounts -> {
                actions.addView(pillButton(tr("Paid mark करें", "Mark as paid"), Ui.GREEN, iconRes = R.drawable.ic_rupee) {
                    payDialog(listOf(c.id), c.amount) { finish() }
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
        busy(tr("Approve हो रहा है…", "Approving…"), { Api.call(this, "decide", JSONObject().put("claimId", claim.id).put("approve", true)) }) { r ->
            skippedOr(r) { toast("Approved"); finish() }
        }
    }

    private fun reject() {
        askText(tr("Reject का कारण", "Reason for rejection"), tr("जैसे: Bill साफ नहीं है", "e.g. Bill is not clear")) { note ->
            busy(tr("Reject हो रहा है…", "Rejecting…"), { Api.call(this, "decide", JSONObject().put("claimId", claim.id).put("approve", false).put("note", note)) }) { r ->
                skippedOr(r) { toast("Rejected"); finish() }
            }
        }
    }

    private fun loadPhoto(which: String, box: LinearLayout) {
        busy(tr("Photo आ रही है…", "Loading photo…"), { Api.call(this, "billPhoto", JSONObject().put("claimId", claim.id).put("which", which), timeoutMs = 90000) }) { r ->
            val bytes = Base64.decode(r.optString("data"), Base64.DEFAULT)
            showImage(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), box)
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
                }).setPositiveButton("Close", null).show()
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
fun AppCompatActivity.payDialog(ids: List<String>, total: Double, onDone: () -> Unit) {
    var via = ""
    var date = Fmt.today()
    val ref = input(tr("UPI / Bank reference (अगर हो)", "UPI / bank reference (if any)"))
    val dateBtn = text(tr("तारीख: ", "Date: ") + Fmt.date(date), 15f, Ui.BLUE, bold = true) { setPadding(0, dp(12), 0, dp(4)) }
    val box = vertical(18) {
        addView(text(tr("${ids.size} claim · कुल ${Fmt.rupees(total)}", "${ids.size} claims · total ${Fmt.rupees(total)}"), 16f, Ui.TEXT, bold = true))
        addView(text(tr("Payment कैसे किया", "How was it paid"), 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
        addView(chips(Store.PAY_VIA, "") { via = it }, lp())
        addView(ref, lp().margins(context, t = 12))
        addView(dateBtn, lp())
    }
    dateBtn.setOnClickListener { pickDate(date) { d -> date = d; dateBtn.text = tr("तारीख: ", "Date: ") + Fmt.date(d) } }
    val d = AlertDialog.Builder(this).setTitle(tr("Paid mark करें", "Mark as paid")).setView(box)
        .setPositiveButton("Paid", null).setNegativeButton("Cancel", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (via.isBlank()) { toast(tr("Cash / UPI / Bank Transfer चुनें", "Choose Cash, UPI or Bank Transfer")); return@setOnClickListener }
            d.dismiss()
            busy(tr("Save हो रहा है…", "Saving…"), {
                Api.call(this, "markPaid", JSONObject().put("claimIds", JSONArray(ids)).put("via", via)
                    .put("ref", ref.text.toString().trim()).put("date", date))
            }) { r -> skippedOr(r) { toast("${r.optInt("done")} claim paid · ${Fmt.rupees(r.optDouble("total"))}"); onDone() } }
        }
    }
    d.show()
}
