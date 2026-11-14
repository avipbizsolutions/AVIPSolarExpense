package com.avip.expense

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Advances.
 *  - Everyone: advances given to me, how much was adjusted in my claims, balance with me.
 *  - Accounts / approvers (team view): balance of every person; accounts can record a new advance.
 */
class AdvancesActivity : AppCompatActivity() {

    companion object { const val EXTRA_TEAM = "team" }

    private lateinit var body: LinearLayout
    private var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var team = false
    private var data: JSONObject? = null
    private var person = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val u = Session.user(this) ?: run { finish(); return }
        team = intent.getBooleanExtra(EXTRA_TEAM, false) && u.seesTeam
        val bottom = if (!team) pillButton(tr("Advance माँगें", "Request advance"), Ui.SUN, Ui.TEXT, iconRes = R.drawable.ic_plus) {
                startActivity(android.content.Intent(this, AdvanceRequestsActivity::class.java).putExtra(AdvanceRequestsActivity.EXTRA_MODE, AdvanceRequestsActivity.MODE_NEW))
            } else null
        body = screen(if (team) tr("Team के Advance", "Team advances") else tr("मेरे Advance", "My advances"), if (team) tr("हर व्यक्ति का balance", "Balance of each person") else u.name,
            bottom = bottom,
            onRefresh = { load() }, onSwipe = { swipe = it })
        load()
    }

    private var loading = false
    private val cacheKey get() = if (team) "adv_team" else "adv_me"

    private fun load() {
        if (loading) { swipe?.isRefreshing = false; return }
        loading = true
        if (data == null) data = Store.load(this, cacheKey)
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.call(this@AdvancesActivity, "advances", JSONObject().put("team", team)) } }
            loading = false
            swipe?.isRefreshing = false
            if (isFinishing) return@launch
            r.onSuccess { data = it; Store.save(this@AdvancesActivity, cacheKey, it) }
            render()
            r.onFailure { showError(it) }
        }
    }

    private fun render() {
        body.removeAllViews()
        if (loading) body.addView(updatingBar(), lp())
        val o = data ?: run { if (loading) body.addView(skeleton(4)); return }
        val people = o.optJSONArray("people")
        if (team && people != null) {
            val list = (0 until people.length()).map { Balance.from(people.getJSONObject(it)) }
            val withMoney = list.filter { it.advanceGiven != 0.0 || it.toReimburse != 0.0 || it.pendingApproval != 0.0 }
            body.addView(card(16) {
                addView(text(tr("Team के पास कुल Advance", "Total advance with the team"), 13f, Ui.MUTED))
                addView(text(Fmt.rupees(list.sumOf { it.advanceBalance }), 26f, Ui.TEXT, bold = true))
                addView(text(tr("Payment बाकी (approved, अपने पैसे से): ", "To pay (approved, own money): ") + Fmt.rupees(list.sumOf { it.toReimburse }), 13f, Ui.MUTED), lp().margins(context, t = 4))
            }, lp().margins(this, b = 12))
            body.addView(text(tr("व्यक्ति पर tap करें = उसके advance", "Tap a person to see their advances"), 12.5f, Ui.MUTED), lp().margins(this, b = 8))
            (withMoney.ifEmpty { list }).sortedByDescending { it.advanceBalance }.forEach { b ->
                body.addView(card(14) {
                    isClickable = true
                    foreground = context.ripple()
                    if (person == b.name) background = rounded(0xFFFFF8E8.toInt(), dpf(16f), Ui.SUN, dp(2))
                    addView(horizontal {
                        addView(vertical {
                            addView(text(b.name, 15f, Ui.TEXT, bold = true))
                            addView(text(tr("मिला ${Fmt.rupees(b.advanceGiven)} · adjust ${Fmt.rupees(b.advanceAdjusted)}", "Given ${Fmt.rupees(b.advanceGiven)} · adjusted ${Fmt.rupees(b.advanceAdjusted)}") +
                                if (b.toReimburse > 0) tr(" · देना है ${Fmt.rupees(b.toReimburse)}", " · to pay ${Fmt.rupees(b.toReimburse)}") else "", 12.5f, Ui.MUTED))
                        }, lp(0, WRAP, 1f))
                        addView(vertical {
                            gravity = Gravity.END
                            addView(text(Fmt.rupees(b.advanceBalance), 17f, if (b.advanceBalance < 0) Ui.DANGER else Ui.TEXT, bold = true))
                            addView(text(tr("उसके पास", "with them"), 11f, Ui.MUTED))
                        })
                    })
                    setOnClickListener { person = if (person == b.name) "" else b.name; render() }
                }, lp().margins(this, b = 8))
            }
        } else {
            val b = Balance.from(o.optJSONObject("balance"))
            body.addView(card(18) {
                background = gradient(dpf(18f), Ui.BRAND_DARK, Ui.BRAND)
                addView(text(tr("Advance आपके पास", "Advance with you"), 13.5f, 0xCCFFFFFF.toInt()))
                addView(text(Fmt.rupees(b.advanceBalance), 32f, android.graphics.Color.WHITE, bold = true))
            }, lp().margins(this, b = 12))
            body.addView(section(tr("हिसाब", "Summary")) {
                addView(kv(tr("कुल Advance मिला", "Total advance received"), Fmt.rupees(b.advanceGiven)))
                addView(kv(tr("Claims में adjust हुआ (approved)", "Adjusted in claims (approved)"), "− " + Fmt.rupees(b.advanceAdjusted)))
                addView(kv(tr("आपके पास बाकी", "Balance with you"), Fmt.rupees(b.advanceBalance), bold = true))
                if (b.advancePending > 0) addView(kv(tr("Advance वाले claims, approval बाकी", "Advance claims awaiting approval"), Fmt.rupees(b.advancePending)))
            })
            val reqs = AdvRequest.list(Store.load(this, "mine")?.optJSONArray("advRequests"))
            if (reqs.isNotEmpty()) body.addView(section(tr("मेरी Advance requests", "My advance requests")) {
                reqs.take(10).forEach { r ->
                    addView(vertical {
                        setPadding(0, dp(8), 0, dp(4))
                        addView(horizontal {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(text(Fmt.rupees(r.amount) + " · " + r.requestedOn.take(10).let { Fmt.date(it) }, 14.5f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
                            addView(reqPill(r.status))
                        })
                        addView(text(r.purpose + when (r.status) {
                            AdvRequest.APPROVED -> tr(" · Approved by ${r.approvedBy}, Accounts से payment बाकी", " · approved by ${r.approvedBy}, payment from Accounts pending")
                            AdvRequest.REJECTED -> " · " + r.remarks
                            AdvRequest.PAID -> " · " + r.payVia + (if (r.paymentRef.isNotBlank()) " " + r.paymentRef else "") + " · " + r.advanceId
                            else -> ""
                        }, 12.5f, Ui.MUTED))
                    })
                }
            }, lp().margins(this, t = 12))
            body.addView(note(tr("Advance से किया खर्च \"नया Claim\" में \"Advance से\" चुनकर डालें। Approve होते ही यहाँ से कट जाएगा। बचा हुआ Advance Office को लौटाना या अगले खर्च में adjust करना है।", "For spending from an advance, add a \"New claim\" and choose \"From advance\". It is deducted here once approved. Any advance left over is returned to the office or adjusted against later expenses.")))
        }

        val advs = Advance.list(o.optJSONArray("advances")).filter { person.isBlank() || it.member == person }
        body.addView(text(if (person.isBlank()) "Advance history" else tr("$person के Advance", "Advances of $person"), 17f, Ui.TEXT, bold = true), lp().margins(this, t = 8, b = 8))
        if (advs.isEmpty()) body.addView(note(tr("अभी कोई Advance नहीं।", "No advances yet.")))
        advs.forEach { a ->
            body.addView(card(14) {
                addView(horizontal {
                    addView(vertical {
                        if (team) addView(text(a.member, 15f, Ui.TEXT, bold = true))
                        addView(text(Fmt.date(a.date) + " · " + a.via + if (a.ref.isNotBlank()) " · " + a.ref else "", 12.5f, Ui.MUTED))
                        if (a.purpose.isNotBlank()) addView(text(a.purpose, 13.5f, Ui.TEXT))
                        addView(text(a.id + " · " + a.givenBy, 11.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(Fmt.rupees(a.amount), 17f, Ui.GREEN, bold = true))
                })
            }, lp().margins(this, b = 8))
        }
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    private fun giveAdvance() {
        val members = Store.load(this, "boot")?.optJSONArray("members")
        val people = (0 until (members?.length() ?: 0)).map { members!!.getJSONObject(it) }
        if (people.isEmpty()) { toast(tr("Team list नहीं मिली। Home पर refresh करें।", "Team list not found. Refresh on the home screen.")); return }
        var memberId = ""
        var via = ""
        var date = Fmt.today()
        val who = text(req(tr("व्यक्ति चुनें", "Choose person")), 16f, Ui.MUTED) {
            background = rounded(android.graphics.Color.WHITE, dpf(12f), 0xFFC8BDD8.toInt(), dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        who.setOnClickListener {
            val names = people.map { it.optString("name") + " · " + it.optString("team") }
            AlertDialog.Builder(this).setTitle(tr("किसे Advance", "Advance to")).setItems(names.toTypedArray()) { _, i ->
                memberId = people[i].optString("id"); who.text = names[i]; who.setTextColor(Ui.TEXT)
            }.show()
        }
        val amount = input("Amount (₹)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, required = true)
        val ref = input(tr("UPI / Bank reference (अगर हो)", "UPI / bank reference (if any)"))
        val purpose = input(tr("किस काम के लिए", "Purpose"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val dateBtn = text(tr("तारीख: ", "Date: ") + Fmt.date(date), 15f, Ui.BLUE, bold = true) { setPadding(0, dp(12), 0, dp(4)) }
        dateBtn.setOnClickListener { pickDate(date) { d -> date = d; dateBtn.text = tr("तारीख: ", "Date: ") + Fmt.date(d) } }
        val box = vertical(18) {
            addView(who, lp())
            addView(amount, lp().margins(context, t = 10))
            addView(text(req(tr("कैसे दिया", "Paid via")), 13f, Ui.MUTED, bold = true), lp().margins(context, t = 12, b = 6))
            addView(chips(Store.PAY_VIA, "") { via = it }, lp())
            addView(ref, lp().margins(context, t = 10))
            addView(purpose, lp().margins(context, t = 10))
            addView(dateBtn, lp())
        }
        val d = AlertDialog.Builder(this).setTitle(tr("Advance दें", "Give advance"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(tr("Save करें", "Save"), null).setNegativeButton(tr("रद्द करें", "Cancel"), null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = amount.text.toString().toDoubleOrNull() ?: 0.0
                if (memberId.isBlank()) { toast(tr("व्यक्ति चुनें", "Choose person")); return@setOnClickListener }
                if (a <= 0) { amount.error = tr("Amount डालें", "Enter the amount"); return@setOnClickListener }
                if (via.isBlank()) { toast(tr("Cash / UPI / Bank Transfer चुनें", "Choose Cash, UPI or Bank Transfer")); return@setOnClickListener }
                d.dismiss()
                val clientId = "GA-" + System.currentTimeMillis()
                sendOrQueue(tr("Save हो रहा है…", "Saving…"), "addAdvance", JSONObject().put("memberId", memberId).put("amount", a).put("via", via)
                    .put("ref", ref.text.toString().trim()).put("purpose", purpose.text.toString().trim()).put("date", date).put("clientId", clientId),
                    "Give advance · " + Fmt.rupees(a), listOf(clientId)) { r ->
                    if (!r.optBoolean("queued")) toast(tr("Advance ${r.optString("id")} save हो गया", "Advance ${r.optString("id")} saved"))
                    load()
                }
            }
        }
        d.show()
    }
}
