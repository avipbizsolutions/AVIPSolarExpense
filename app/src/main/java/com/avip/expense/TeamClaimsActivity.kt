package com.avip.expense

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Team claims for approvers and accounts.
 *  - Approve: pending claims of others; tick several and approve together.
 *  - Pay: approved "own money" claims; tick several and mark paid together.
 *  - All: every claim, filtered by status and person.
 */
class TeamClaimsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_APPROVE = "approve"
        const val MODE_PAY = "pay"
        const val MODE_ALL = "all"
    }

    private lateinit var mode: String
    private lateinit var body: LinearLayout
    private lateinit var actionBtn: View
    private var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var claims = listOf<Claim>()
    private val selected = linkedSetOf<String>()
    private var status = ""
    private var person = ""
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ALL
        val title = when (mode) { MODE_APPROVE -> tr("Claims Approve करें", "Approve claims"); MODE_PAY -> tr("Payment करें", "Pay claims"); else -> tr("Team के Claims", "Team claims") }
        val bottom = when (mode) {
            MODE_APPROVE -> pillButton(tr("चुने हुए Approve करें", "Approve selected"), Ui.GREEN, iconRes = R.drawable.ic_check) { approveSelected() }
            MODE_PAY -> pillButton(tr("चुने हुए Paid mark करें", "Mark selected as paid"), Ui.GREEN, iconRes = R.drawable.ic_rupee) { paySelected() }
            else -> null
        }
        body = screen(title, "Tap = details" + if (bottom != null) tr(" · ☐ = चुनें", " · ☐ = select") else "", right = listOf(barButton(R.drawable.ic_refresh) { load() }), bottom = bottom,
            onRefresh = { load() }, onSwipe = { swipe = it })
        if (bottom != null) actionBtn = bottom
        status = when (mode) { MODE_APPROVE -> Status.PENDING; MODE_PAY -> Status.APPROVED; else -> "" }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** Claims from the last load, filtered for this screen, so the list shows at once. */
    private fun fromCache(): List<Claim> {
        val waitingRefs = Actions.pendingRefs(this)
        val me = Session.user(this)
        return Claim.list(Store.load(this, "team")?.optJSONArray("claims")).filter {
            when (mode) {
                MODE_APPROVE -> it.status == Status.PENDING && it.memberId != me?.id && me?.canDecide(it, Settings.approvalLimit(this)) == true && it.id !in waitingRefs
                MODE_PAY -> it.status == Status.APPROVED && it.paidBy == "Self" && it.id !in waitingRefs
                else -> true
            }
        }
    }

    private fun load() {
        if (loading) { swipe?.isRefreshing = false; return }
        loading = true
        if (claims.isEmpty()) claims = fromCache()
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Api.call(this@TeamClaimsActivity, "teamClaims", JSONObject().put("status", if (mode == MODE_ALL) "" else status)) }
            }
            r.onSuccess { o ->
                // keep the list so the claim screen can open any of them
                val keep = Store.load(this@TeamClaimsActivity, "team") ?: JSONObject()
                val merged = JSONArray()
                val ids = mutableSetOf<String>()
                val asked = if (mode == MODE_ALL) "" else status
                listOf(o.optJSONArray("claims"), keep.optJSONArray("claims")).forEachIndexed { n, a ->
                    for (i in 0 until (a?.length() ?: 0)) {
                        val x = a!!.getJSONObject(i)
                        // the fresh list is complete for the status asked: old copies with that status are out of date
                        if (n == 1 && (asked.isBlank() || x.optString("status") == asked)) continue
                        if (ids.add(x.optString("id"))) merged.put(x)
                    }
                }
                Store.save(this@TeamClaimsActivity, "team", JSONObject().put("claims", merged))
                val me = Session.user(this@TeamClaimsActivity)
                claims = Claim.list(o.optJSONArray("claims")).filter {
                    when (mode) {
                        MODE_APPROVE -> it.memberId != me?.id && me?.canDecide(it, Settings.approvalLimit(this@TeamClaimsActivity)) == true
                        MODE_PAY -> it.paidBy == "Self"
                        else -> true
                    }
                }
                selected.retainAll(claims.map { it.id }.toSet())
            }
            loading = false
            swipe?.isRefreshing = false
            if (isFinishing) return@launch
            render()
            r.onFailure { showError(it) }
        }
    }

    private fun render() {
        body.removeAllViews()
        if (loading) body.addView(updatingBar(), lp())
        val me = Session.user(this)
        if (mode == MODE_APPROVE && me != null && !me.isAdmin) {
            val limit = Settings.approvalLimit(this)
            val waiting = Claim.list(Store.load(this, "team")?.optJSONArray("claims"))
                .count { it.status == Status.PENDING && it.memberId != me.id && !me.canDecide(it, limit) }
            if (waiting > 0) body.addView(note(tr("$waiting claim (${Fmt.rupees(limit)} से ऊपर या Manager के) सिर्फ Atul या Rahul approve करेंगे।",
                "$waiting claims (above ${Fmt.rupees(limit)} or from managers) can be approved only by Atul or Rahul."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(this, b = 10))
        }
        val people = claims.map { it.member }.filter { it.isNotBlank() }.distinct().sorted()
        if (mode == MODE_ALL) {
            body.addView(chips(listOf(Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).map { Status.label(it) },
                if (status.isBlank()) "" else Status.label(status)) { pick ->
                status = listOf(Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).firstOrNull { Status.label(it) == pick } ?: ""
                render()
            }, lp().margins(this, b = 8))
        }
        if (people.size > 1) body.addView(chips(people, person) { person = it; render() }, lp().margins(this, b = 10))

        val shown = claims.filter { (status.isBlank() || mode != MODE_ALL || it.status == status) && (person.isBlank() || it.member == person) }
        val total = shown.sumOf { it.amount }
        body.addView(horizontal {
            addView(text("${shown.size} claim", 14f, Ui.MUTED, bold = true), lp(0, WRAP, 1f))
            addView(text(tr("कुल ", "Total ") + Fmt.rupees(total), 15f, Ui.TEXT, bold = true))
        }, lp().margins(this, b = 8))

        val selectable = mode != MODE_ALL
        if (selectable && shown.isNotEmpty()) {
            val all = shown.all { it.id in selected }
            body.addView(text(if (all) tr("☑ सब हटाएँ", "☑ Clear all") else tr("☐ सब चुनें", "☐ Select all"), 14f, Ui.BLUE, bold = true) {
                setPadding(dp(4), dp(6), dp(4), dp(10))
                setOnClickListener { if (all) selected.removeAll(shown.map { it.id }.toSet()) else selected.addAll(shown.map { it.id }); render() }
            })
        }
        if (shown.isEmpty() && !loading) body.addView(note(when (mode) {
            MODE_APPROVE -> tr("कोई claim approval के लिए बाकी नहीं है।", "No claims waiting for approval.")
            MODE_PAY -> tr("कोई approved claim payment के लिए बाकी नहीं है।", "No approved claims waiting for payment.")
            else -> tr("कोई claim नहीं।", "No claims.")
        }))
        shown.forEach { c ->
            val row = claimRow(c, showMember = true, selected = if (selectable) c.id in selected else null) {
                startActivity(Intent(this, ClaimActivity::class.java).putExtra(ClaimActivity.EXTRA_ID, c.id))
            }
            if (selectable) row.setOnLongClickListener { toggle(c.id); true }
            if (selectable) (row as LinearLayout).getChildAt(0).let { line ->
                (line as LinearLayout).getChildAt(0).setOnClickListener { toggle(c.id) }
            }
            body.addView(row, lp().margins(this, b = 8))
        }
        if (::actionBtn.isInitialized) {
            val sum = claims.filter { it.id in selected }.sumOf { it.amount }
            actionBtn.alpha = if (selected.isEmpty()) 0.45f else 1f
            ((actionBtn as LinearLayout).getChildAt((actionBtn as LinearLayout).childCount - 1) as android.widget.TextView).text =
                (if (mode == MODE_APPROVE) tr("Approve करें", "Approve") else tr("Paid mark करें", "Mark as paid")) + if (selected.isEmpty()) "" else " · ${selected.size} · ${Fmt.rupees(sum)}"
        }
    }

    private fun toggle(id: String) {
        if (!selected.remove(id)) selected.add(id)
        render()
    }

    private fun approveSelected() {
        if (selected.isEmpty()) { toast(tr("पहले claims चुनें (☐ पर tap करें)", "Select claims first (tap ☐)")); return }
        val ids = selected.toList()
        val sum = claims.filter { it.id in selected }.sumOf { it.amount }
        confirm(tr("Approve करें?", "Approve?"), tr("${ids.size} claim · कुल ${Fmt.rupees(sum)}", "${ids.size} claims · total ${Fmt.rupees(sum)}")) {
            sendOrQueue(tr("Approve हो रहा है…", "Approving…"), "decide", JSONObject().put("claimIds", JSONArray(ids)).put("approve", true),
                "Approve · ${ids.size} claim · ${Fmt.rupees(sum)}", ids) { r ->
                skippedOr(r) { if (!r.optBoolean("queued")) toast("${r.optInt("done")} claim approved"); selected.clear(); if (r.optBoolean("queued")) render() else load() }
            }
        }
    }

    private fun paySelected() {
        if (selected.isEmpty()) { toast(tr("पहले claims चुनें (☐ पर tap करें)", "Select claims first (tap ☐)")); return }
        val ids = selected.toList()
        payDialog(ids, claims.filter { it.id in selected }.sumOf { it.amount }) { selected.clear(); load() }
    }
}
