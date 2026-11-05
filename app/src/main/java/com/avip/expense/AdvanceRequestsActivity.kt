package com.avip.expense

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One advance request, as the office server sends it. */
data class AdvRequest(
    val id: String, val requestedOn: String, val member: String, val memberId: String, val team: String,
    val amount: Double, val purpose: String, val neededBy: String, val payVia: String, val status: String,
    val approvedBy: String, val approvedOn: String, val remarks: String, val paidOn: String, val paidBy: String,
    val paymentRef: String, val advanceId: String, val adminOnly: Boolean, val adminReason: String, val canDecide: Boolean,
) {
    val key get() = memberId.ifBlank { member }

    companion object {
        const val PENDING = "Pending"; const val APPROVED = "Approved"; const val REJECTED = "Rejected"; const val PAID = "Paid"
        const val QUEUED = "Queued" // on the phone, waiting for internet

        fun from(o: JSONObject) = AdvRequest(
            o.optString("id"), o.optString("requestedOn"), o.optString("member"), o.optString("memberId"), o.optString("team"),
            o.optDouble("amount", 0.0), o.optString("purpose"), o.optString("neededBy"), o.optString("payVia"),
            o.optString("status", PENDING), o.optString("approvedBy"), o.optString("approvedOn"), o.optString("remarks"),
            o.optString("paidOn"), o.optString("paidBy"), o.optString("paymentRef"), o.optString("advanceId"),
            o.optBoolean("adminOnly"), o.optString("adminReason"), o.optBoolean("canDecide"),
        )

        fun list(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { from(a!!.getJSONObject(it)) }

        /** "Approved" means approved but not paid yet: the money is not with the person. */
        fun label(s: String) = when (s) {
            PENDING -> tr("Approval बाकी", "Awaiting approval")
            APPROVED -> tr("Approved, payment बाकी", "Approved, not paid")
            REJECTED -> "Rejected"
            PAID -> tr("Paid · मिल गया", "Paid")
            QUEUED -> tr("Internet का इंतज़ार", "Waiting for internet")
            else -> s
        }

        fun colors(s: String): Pair<Int, Int> = when (s) {
            PENDING -> Ui.SUN_LIGHT to Ui.SUN_TEXT
            APPROVED -> 0xFFE8F0FA.toInt() to 0xFF1E56A0.toInt()
            REJECTED -> 0xFFFCE8E6.toInt() to Ui.DANGER
            PAID -> 0xFFE5F4EA.toInt() to Ui.GREEN
            else -> Ui.BRAND_LIGHT to Ui.TEXT
        }
    }
}

fun android.content.Context.reqPill(status: String): View {
    val (bg, fg) = AdvRequest.colors(status)
    return pill(AdvRequest.label(status), bg, fg)
}

/** A person's earlier advances, for approvers. */
data class AdvHistory(val balance: Balance, val advances: List<JSONObject>, val openCount: Int, val requests: List<AdvRequest>) {
    companion object {
        fun from(o: JSONObject?) = AdvHistory(
            Balance.from(o?.optJSONObject("balance")),
            (0 until (o?.optJSONArray("advances")?.length() ?: 0)).map { o!!.getJSONArray("advances").getJSONObject(it) },
            o?.optInt("openCount") ?: 0,
            AdvRequest.list(o?.optJSONArray("requests")),
        )
    }
}

/**
 * Advance requests.
 *  - MODE_NEW: a team member asks for an advance.
 *  - MODE_APPROVE: approvers see requests with each person's earlier advances; tap = details.
 *  - MODE_PAY: accounts pays approved requests (then they count as advance given).
 *  - MODE_DETAIL (with EXTRA_ID): one request, the person's earlier advances, Approve / Reject or Pay.
 */
class AdvanceRequestsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_ID = "id"
        const val MODE_NEW = "new"
        const val MODE_APPROVE = "approve"
        const val MODE_PAY = "pay"
        const val MODE_DETAIL = "detail"
        private const val CACHE = "adv_req_team"
    }

    private lateinit var body: LinearLayout
    private var mode = MODE_APPROVE
    private var data: JSONObject? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val u = Session.user(this) ?: run { finish(); return }
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_APPROVE
        when (mode) {
            MODE_NEW -> buildForm(u)
            else -> {
                if (!u.seesTeam) { finish(); return }
                val title = when (mode) {
                    MODE_PAY -> tr("Advance Payment करें", "Pay advances")
                    MODE_DETAIL -> tr("Advance Request", "Advance request")
                    else -> tr("Advance Approve करें", "Approve advances")
                }
                body = screen(title, tr("पुराने advance का हाल साथ में", "With each person's earlier advances"),
                    right = listOf(barButton(R.drawable.ic_refresh) { load() }))
                load()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode != MODE_NEW && ::body.isInitialized && data != null) { data = Store.load(this, CACHE) ?: data; render() }
    }

    // ------------------------------------------------------------------ request form (team member)

    private fun buildForm(u: User) {
        var payVia = ""
        var needed = ""
        lateinit var amount: EditText
        lateinit var purpose: EditText
        val limit = Settings.approvalLimit(this)
        val send = pillButton(tr("Approval के लिए भेजें", "Send for approval"), Ui.SUN, Ui.TEXT, heightDp = 54) {
            val a = amount.text.toString().trim().toDoubleOrNull() ?: 0.0
            if (a <= 0) { amount.error = tr("Amount डालें", "Enter the amount"); return@pillButton }
            if (purpose.text.toString().trim().length < 3) { purpose.error = tr("किस काम के लिए, लिखें", "Write what it is for"); return@pillButton }
            if (payVia.isBlank()) { toast(tr("Cash / UPI / Bank चुनें", "Choose Cash, UPI or Bank")); return@pillButton }
            val id = "ARQ-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date()) + "-" + u.id.uppercase().replace(Regex("[^A-Z0-9]"), "")
            val req = JSONObject().put("id", id).put("amount", a).put("purpose", purpose.text.toString().trim())
                .put("neededBy", needed).put("payVia", payVia)
            sendOrQueue(tr("भेजा जा रहा है…", "Sending…"), "requestAdvance", JSONObject().put("request", req),
                "Advance request · " + Fmt.rupees(a) + " · " + purpose.text.toString().trim(), listOf(id)) { r ->
                if (!r.optBoolean("queued")) toast(tr("Advance request भेज दी। Approval होते ही आपको दिखेगा।", "Advance request sent. You will see it here once it is approved."))
                finish()
            }
        }
        val b = screen(tr("Advance माँगें", "Request advance"), tr("Approval के बाद Accounts payment करेगा", "Accounts pays after approval"), bottom = send)
        val mine = Store.load(this, "mine")
        val bal = Balance.from(mine?.optJSONObject("balance"))
        b.addView(card(16) {
            background = rounded(Ui.BRAND_LIGHT, dpf(16f))
            addView(text(tr("अभी आपके पास", "With you now"), 14f, Ui.TEXT, bold = true))
            addView(kv(tr("Advance balance", "Advance balance"), Fmt.rupees(bal.advanceBalance), bold = true))
            if (bal.advancePending > 0) addView(kv(tr("Advance वाले claims, approval बाकी", "Advance claims awaiting approval"), Fmt.rupees(bal.advancePending)))
            val open = AdvRequest.list(mine?.optJSONArray("advRequests")).filter { it.status == AdvRequest.PENDING || it.status == AdvRequest.APPROVED }
            if (open.isNotEmpty()) addView(text(tr("पहले से माँगा: ", "Already requested: ") + open.joinToString { Fmt.rupees(it.amount) + " (" + AdvRequest.label(it.status) + ")" }, 12.5f, Ui.MUTED), lp().margins(context, t = 4))
        }, lp().margins(this, b = 12))
        b.addView(text(req(tr("ज़रूरी", "Required")), 12.5f, Ui.MUTED) { gravity = Gravity.END }, lp().margins(this, b = 6))
        b.addView(section(tr("Request", "Request")) {
            amount = field(this, tr("कितना चाहिए (₹)", "Amount needed (₹)"), "", tr("जैसे 2000", "e.g. 2000"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, R.drawable.ic_rupee, 9, required = true) {}
            amount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            purpose = field(this, tr("किस काम के लिए", "What it is for"), "", tr("जैसे Gaya site – labour + transport", "e.g. Gaya site – labour + transport"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit, 300, required = true) {}
            val neededBtn = text(tr("कब तक चाहिए: चुनें", "Needed by: choose"), 15f, Ui.BRAND, bold = true) { setPadding(0, dp(14), 0, dp(6)) }
            neededBtn.setOnClickListener { pickDate(needed.ifBlank { Fmt.today() }) { d -> needed = d; neededBtn.text = tr("कब तक चाहिए: ", "Needed by: ") + Fmt.date(d) } }
            addView(neededBtn)
            addView(text(req(tr("Payment कैसे चाहिए", "How to pay")), 13f, Ui.MUTED, bold = true), lp().margins(context, t = 8, b = 6))
            addView(chips(listOf("Cash", "UPI", "Bank Transfer"), "") { payVia = it })
        })
        b.addView(note(tr("${Fmt.rupees(limit)} से ऊपर का advance, और Manager का advance, सिर्फ Atul या Rahul approve करेंगे।", "Advances above ${Fmt.rupees(limit)}, and advances for managers, are approved only by Atul or Rahul."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(this, t = 12))
    }

    // ------------------------------------------------------------------ team lists

    private fun load() {
        if (loading) return
        loading = true
        if (data == null) data = Store.load(this, CACHE)
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.call(this@AdvanceRequestsActivity, "advanceRequests", JSONObject().put("team", true)) } }
            loading = false
            if (isFinishing) return@launch
            r.onSuccess { data = it; Store.save(this@AdvanceRequestsActivity, CACHE, it) }
            render()
            r.onFailure { showError(it) }
        }
    }

    private fun history(r: AdvRequest) = AdvHistory.from(data?.optJSONObject("history")?.optJSONObject(r.key))

    private fun render() {
        body.removeAllViews()
        if (loading) body.addView(updatingBar(), lp())
        val o = data ?: return
        val all = AdvRequest.list(o.optJSONArray("requests"))
        val u = Session.user(this) ?: return
        when (mode) {
            MODE_DETAIL -> {
                val r = all.firstOrNull { it.id == intent.getStringExtra(EXTRA_ID) }
                if (r == null) { body.addView(note(tr("Request नहीं मिली।", "Request not found."))); return }
                renderDetail(r, u)
            }
            MODE_PAY -> {
                val waiting = Actions.pendingRefs(this)
                val list = all.filter { it.status == AdvRequest.APPROVED && it.id !in waiting }
                body.addView(text(tr("${list.size} advance approved, payment बाकी · कुल ${Fmt.rupees(list.sumOf { it.amount })}", "${list.size} approved, not paid · total ${Fmt.rupees(list.sumOf { it.amount })}"), 14f, Ui.MUTED, bold = true), lp().margins(this, b = 10))
                if (list.isEmpty()) body.addView(note(tr("Payment के लिए कोई advance नहीं।", "No advances waiting for payment.")))
                list.forEach { r -> body.addView(requestCard(r) { openDetail(r) }, lp().margins(this, b = 8)) }
            }
            else -> {
                val waitingRefs = Actions.pendingRefs(this)
                val list = all.filter { it.canDecide && it.id !in waitingRefs }
                val waiting = all.count { it.status == AdvRequest.PENDING && !it.canDecide && it.memberId != u.id }
                body.addView(text(tr("${list.size} request · कुल ${Fmt.rupees(list.sumOf { it.amount })}", "${list.size} requests · total ${Fmt.rupees(list.sumOf { it.amount })}"), 14f, Ui.MUTED, bold = true), lp().margins(this, b = 10))
                if (waiting > 0 && !u.isAdmin) body.addView(note(tr("$waiting request (बड़ी रकम या Manager की) सिर्फ Atul या Rahul approve करेंगे।", "$waiting requests (large amounts or from managers) can be approved only by Atul or Rahul."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(this, b = 10))
                if (list.isEmpty()) body.addView(note(tr("Approve करने के लिए कोई advance request नहीं।", "No advance requests to approve.")))
                list.forEach { r -> body.addView(requestCard(r) { openDetail(r) }, lp().margins(this, b = 8)) }
                val decided = all.filter { it.status != AdvRequest.PENDING }.take(15)
                if (decided.isNotEmpty()) {
                    body.addView(text(tr("हाल के फैसले", "Recently decided"), 16f, Ui.TEXT, bold = true), lp().margins(this, t = 12, b = 8))
                    decided.forEach { r -> body.addView(requestCard(r, compact = true) { openDetail(r) }, lp().margins(this, b = 8)) }
                }
            }
        }
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    private fun openDetail(r: AdvRequest) {
        startActivity(Intent(this, AdvanceRequestsActivity::class.java).putExtra(EXTRA_MODE, MODE_DETAIL).putExtra(EXTRA_ID, r.id))
    }

    private fun requestCard(r: AdvRequest, compact: Boolean = false, onClick: () -> Unit): View = card(14) {
        isClickable = true
        foreground = context.ripple()
        val h = history(r)
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(vertical {
                addView(text(r.member, 15.5f, Ui.TEXT, bold = true))
                addView(text(r.team + (if (r.neededBy.isNotBlank()) tr(" · चाहिए ", " · needed ") + Fmt.date(r.neededBy) else ""), 12f, Ui.MUTED))
            }, lp(0, WRAP, 1f))
            addView(text(Fmt.rupees(r.amount), 20f, Ui.TEXT, bold = true))
        })
        addView(text(r.purpose, 13.5f, Ui.TEXT), lp().margins(context, t = 4))
        addView(horizontal {
            if (compact) addView(reqPill(r.status), lp().margins(context, r = 6))
            else {
                addView(pill(tr("पास में ", "Holds ") + Fmt.rupees(h.balance.advanceBalance), Ui.BRAND_LIGHT, Ui.BRAND_DARK), lp().margins(context, r = 6))
                if (h.balance.advancePending > 0) addView(pill(tr("हिसाब बाकी ", "To settle ") + Fmt.rupees(h.balance.advancePending), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(context, r = 6))
                addView(if (h.openCount > 0) pill(tr("${h.openCount} पुराने खुले", "${h.openCount} still open"), 0xFFFCE8E6.toInt(), Ui.DANGER)
                    else pill(tr("सब settled", "All settled"), 0xFFE5F4EA.toInt(), Ui.GREEN))
            }
        }, lp().margins(context, t = 8))
        setOnClickListener { onClick() }
    }

    private fun renderDetail(r: AdvRequest, u: User) {
        val h = history(r)
        body.addView(card(16) {
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(Fmt.rupees(r.amount), 28f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(reqPill(r.status))
            })
            addView(kv(tr("किसका", "For"), r.member + " · " + r.team))
            addView(kv(tr("किस लिए", "Purpose"), r.purpose))
            if (r.neededBy.isNotBlank()) addView(kv(tr("कब तक", "Needed by"), Fmt.date(r.neededBy)))
            addView(kv("Payment", r.payVia))
            addView(kv(tr("माँगा", "Requested"), r.requestedOn))
            if (r.approvedBy.isNotBlank()) addView(kv(if (r.status == AdvRequest.REJECTED) "Rejected by" else "Approved by", r.approvedBy + " · " + r.approvedOn))
            if (r.remarks.isNotBlank()) addView(kv("Remarks", r.remarks))
            if (r.status == AdvRequest.PAID) addView(kv("Paid", Fmt.date(r.paidOn) + " · " + r.payVia + (if (r.paymentRef.isNotBlank()) " · " + r.paymentRef else "") + " · " + r.advanceId))
        }, lp().margins(this, b = 12))

        body.addView(section(tr("पुराने Advance का हाल", "Earlier advances")) {
            addView(horizontal {
                for ((v, l, bg, fg) in listOf(
                    Quad(h.balance.advanceGiven, tr("कुल मिला", "Total given"), Ui.BRAND_LIGHT, Ui.BRAND_DARK),
                    Quad(h.balance.advanceAdjusted, tr("Claims में adjust", "Settled by claims"), 0xFFE5F4EA.toInt(), Ui.GREEN),
                    Quad(h.balance.advanceBalance, tr("अभी पास में", "With them now"), Ui.SUN_LIGHT, Ui.SUN_TEXT),
                )) addView(vertical(8) {
                    background = rounded(bg, dpf(12f))
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(text(Fmt.rupees(v), 15.5f, fg, bold = true) { gravity = Gravity.CENTER })
                    addView(text(l, 11f, Ui.MUTED) { gravity = Gravity.CENTER })
                }, lp(0, WRAP, 1f).margins(context, r = 6))
            }, lp().margins(context, t = 4, b = 6))
            if (h.advances.isEmpty()) addView(text(tr("पहले कोई advance नहीं मिला।", "No earlier advances."), 13.5f, Ui.MUTED), lp().margins(context, t = 6))
            h.advances.forEach { a ->
                val settled = a.optBoolean("settled")
                addView(vertical {
                    setPadding(0, dp(10), 0, dp(4))
                    addView(horizontal {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(a.optString("id") + " · " + Fmt.date(a.optString("date")), 14f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                        addView(if (settled) pill("Settled", 0xFFE5F4EA.toInt(), Ui.GREEN) else pill(tr("खुला", "Open"), 0xFFFCE8E6.toInt(), Ui.DANGER))
                    })
                    addView(text(tr("मिला ", "Given ") + Fmt.rupees(a.optDouble("amount")) + tr(" · adjust ", " · settled ") + Fmt.rupees(a.optDouble("used")) +
                        tr(" · बाकी ", " · left ") + Fmt.rupees(a.optDouble("left")), 12.5f, Ui.MUTED))
                })
            }
            if (h.balance.advancePending > 0) addView(text(tr("Advance वाले claims, approval बाकी: ", "Advance claims awaiting approval: ") + Fmt.rupees(h.balance.advancePending), 12.5f, Ui.SUN_TEXT, bold = true), lp().margins(context, t = 8))
            val others = h.requests.filter { it.id != r.id && (it.status == AdvRequest.PENDING || it.status == AdvRequest.APPROVED) }
            if (others.isNotEmpty()) addView(text(tr("दूसरी खुली requests: ", "Other open requests: ") + others.joinToString { Fmt.rupees(it.amount) + " (" + AdvRequest.label(it.status) + ")" }, 12.5f, Ui.SUN_TEXT, bold = true), lp().margins(context, t = 6))
        }, lp().margins(this, b = 12))

        when {
            r.canDecide -> body.addView(horizontal {
                addView(pillButton("Reject", 0xFFFCE8E6.toInt(), Ui.DANGER, heightDp = 52) { decide(r, false) }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton("Approve", Ui.GREEN, heightDp = 52) { decide(r, true) }, lp(0, WRAP, 1f))
            }, lp())
            r.status == AdvRequest.PENDING && r.memberId != u.id && u.canApprove -> body.addView(note(
                tr("यह advance (${r.adminReason.ifBlank { "बड़ी रकम" }}) सिर्फ Atul या Rahul approve कर सकते हैं।", "Only Atul or Rahul can approve this advance (${r.adminReason.ifBlank { "large amount" }})."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
            r.status == AdvRequest.APPROVED && u.isAccounts -> body.addView(pillButton(tr("Payment किया — Mark paid", "Mark paid"), Ui.GREEN, heightDp = 52) { pay(r) }, lp())
        }
    }

    private data class Quad(val v: Double, val l: String, val bg: Int, val fg: Int)

    private fun decide(r: AdvRequest, approve: Boolean) {
        val noteBox = input(if (approve) tr("Remark (अगर हो)", "Remark (optional)") else tr("Reject करने का कारण", "Reason for rejecting"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, required = !approve)
        val d = AlertDialog.Builder(this)
            .setTitle(if (approve) tr("${Fmt.rupees(r.amount)} approve करें?", "Approve ${Fmt.rupees(r.amount)}?") else tr("Reject करें?", "Reject?"))
            .setView(vertical(18) { addView(noteBox, lp()) })
            .setPositiveButton(if (approve) "Approve" else "Reject", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = noteBox.text.toString().trim()
                if (!approve && n.length < 3) { noteBox.error = tr("कारण लिखें", "Write the reason"); return@setOnClickListener }
                d.dismiss()
                sendOrQueue(tr("Save हो रहा है…", "Saving…"), "decideAdvance", JSONObject().put("requestId", r.id).put("approve", approve).put("note", n),
                    (if (approve) "Approve" else "Reject") + " advance · " + r.member + " · " + Fmt.rupees(r.amount), listOf(r.id)) { res ->
                    if (res.optBoolean("queued")) { finish(); return@sendOrQueue }
                    val skipped = res.optJSONArray("skipped")
                    if (res.optInt("done") == 0 && skipped != null && skipped.length() > 0) toast(skipped.optString(0))
                    else toast(if (approve) tr("Approve हो गया। Accounts payment करेगा।", "Approved. Accounts will pay it.") else tr("Reject हो गया", "Rejected"))
                    refreshAndClose()
                }
            }
        }
        d.show()
    }

    private fun pay(r: AdvRequest) {
        var via = r.payVia
        val ref = input(tr("UPI / Bank reference (अगर हो)", "UPI / bank reference (if any)"))
        val box = vertical(18) {
            addView(text(r.member + " · " + Fmt.rupees(r.amount), 16f, Ui.TEXT, bold = true))
            addView(text(req(tr("कैसे दिया", "Paid via")), 13f, Ui.MUTED, bold = true), lp().margins(context, t = 10, b = 6))
            addView(chips(listOf("Cash", "UPI", "Bank Transfer"), via) { via = it })
            addView(ref, lp().margins(context, t = 10))
        }
        val d = AlertDialog.Builder(this).setTitle(tr("Advance payment", "Advance payment")).setView(box)
            .setPositiveButton("Mark paid", null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (via.isBlank()) { toast(tr("Cash / UPI / Bank चुनें", "Choose Cash, UPI or Bank")); return@setOnClickListener }
                d.dismiss()
                sendOrQueue(tr("Save हो रहा है…", "Saving…"), "payAdvance", JSONObject().put("requestId", r.id).put("via", via).put("ref", ref.text.toString().trim()),
                    "Pay advance · " + r.member + " · " + Fmt.rupees(r.amount), listOf(r.id)) { res ->
                    if (res.optBoolean("queued")) { finish(); return@sendOrQueue }
                    toast(tr("Paid · ${res.optString("advanceId")} बन गया", "Paid · ${res.optString("advanceId")} recorded"))
                    refreshAndClose()
                }
            }
        }
        d.show()
    }

    /** After a decision: fresh list into the cache, then back to the list. */
    private fun refreshAndClose() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { Api.call(this@AdvanceRequestsActivity, "advanceRequests", JSONObject().put("team", true)) } }
                .onSuccess { Store.save(this@AdvanceRequestsActivity, CACHE, it) }
            if (mode == MODE_DETAIL) finish() else load()
        }
    }
}
