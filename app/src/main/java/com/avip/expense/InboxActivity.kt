package com.avip.expense

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
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
 * One screen for the two things a reviewer does, with a Claims / Advances toggle:
 *  - MODE_APPROVE (approvers): approve pending claims (tick several) and advance requests (tap to review).
 *  - MODE_PAY (accounts): pay approved claims (tick several) and approved advances (tap to pay).
 * TeamClaimsActivity and AdvanceRequestsActivity still handle the details and deep links.
 */
class InboxActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_APPROVE = "approve"
        const val MODE_PAY = "pay"
        private const val TAB_CLAIMS = "claims"
        private const val TAB_ADV = "advances"
    }

    private lateinit var mode: String
    private lateinit var body: LinearLayout
    private var actionBtn: View? = null
    private var tab = TAB_CLAIMS

    private var claims = listOf<Claim>()
    private var blocked = listOf<Claim>()   // approve mode: pending claims only Atul/Rahul can approve
    private val selected = linkedSetOf<String>()
    private var advData: JSONObject? = null
    private var loadingClaims = false
    private var loadingAdv = false

    private val pay get() = mode == MODE_PAY
    private val claimStatus get() = if (pay) Status.APPROVED else Status.PENDING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_APPROVE
        val title = if (pay) tr("Payment करें", "Payments") else tr("Approve करें", "Approvals")
        actionBtn = if (pay) pillButton(tr("चुने हुए Paid mark करें", "Mark selected as paid"), Ui.GREEN, iconRes = R.drawable.ic_rupee) { payClaims() }
            else pillButton(tr("चुने हुए Approve करें", "Approve selected"), Ui.GREEN, iconRes = R.drawable.ic_check) { approveClaims() }
        body = screen(title, tr("Claims और Advances, एक जगह", "Claims and advances in one place"),
            right = listOf(barButton(R.drawable.ic_refresh) { loadAll() }), bottom = actionBtn)
    }

    override fun onResume() {
        super.onResume()
        loadAll()
    }

    private fun loadAll() { loadClaims(); loadAdvances() }

    // ------------------------------------------------------------------ claims (tick several)

    private fun claimsFromCache(): List<Claim> {
        val waiting = Actions.pendingRefs(this)
        val me = Session.user(this)
        return Claim.list(Store.load(this, "team")?.optJSONArray("claims")).filter {
            if (pay) it.status == Status.APPROVED && it.paidBy == "Self" && it.id !in waiting
            else it.status == Status.PENDING && it.memberId != me?.id && me?.canDecide(it, Settings.approvalLimit(this)) == true && it.id !in waiting
        }
    }

    /** Approve mode: pending claims this approver may NOT decide (above the limit or from managers). */
    private fun blockedFromCache(): List<Claim> {
        if (pay) return emptyList()
        val waiting = Actions.pendingRefs(this)
        val me = Session.user(this)
        return Claim.list(Store.load(this, "team")?.optJSONArray("claims")).filter {
            it.status == Status.PENDING && it.memberId != me?.id && me?.canDecide(it, Settings.approvalLimit(this)) != true && it.id !in waiting
        }
    }

    private fun loadClaims() {
        if (loadingClaims) return
        loadingClaims = true
        if (claims.isEmpty()) claims = claimsFromCache()
        if (blocked.isEmpty()) blocked = blockedFromCache()
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.call(this@InboxActivity, "teamClaims", JSONObject().put("status", claimStatus)) } }
            r.onSuccess { o ->
                // keep the full list so the claim screen can open any of them
                val keep = Store.load(this@InboxActivity, "team") ?: JSONObject()
                val merged = JSONArray(); val ids = mutableSetOf<String>()
                listOf(o.optJSONArray("claims"), keep.optJSONArray("claims")).forEachIndexed { n, a ->
                    for (i in 0 until (a?.length() ?: 0)) {
                        val x = a!!.getJSONObject(i)
                        // the fresh list is complete for the status asked: old copies with that status are out of date
                        if (n == 1 && x.optString("status") == claimStatus) continue
                        if (ids.add(x.optString("id"))) merged.put(x)
                    }
                }
                Store.save(this@InboxActivity, "team", JSONObject().put("claims", merged))
                val me = Session.user(this@InboxActivity)
                val limit = Settings.approvalLimit(this@InboxActivity)
                val fresh = Claim.list(o.optJSONArray("claims"))
                claims = fresh.filter {
                    if (pay) it.paidBy == "Self"
                    else it.memberId != me?.id && me?.canDecide(it, limit) == true
                }
                blocked = if (pay) emptyList() else fresh.filter { it.memberId != me?.id && me?.canDecide(it, limit) != true }
                selected.retainAll(claims.map { it.id }.toSet())
            }
            loadingClaims = false
            if (isFinishing) return@launch
            render()
            r.onFailure { showError(it) }
        }
    }

    // ------------------------------------------------------------------ advances (tap to review)

    private fun loadAdvances() {
        if (loadingAdv) return
        loadingAdv = true
        if (advData == null) advData = Store.load(this, "adv_req_team")
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.call(this@InboxActivity, "advanceRequests", JSONObject().put("team", true)) } }
            loadingAdv = false
            if (isFinishing) return@launch
            r.onSuccess { advData = it; Store.save(this@InboxActivity, "adv_req_team", it) }
            render()
            r.onFailure { showError(it) }
        }
    }

    private fun advList(): List<AdvRequest> {
        val waiting = Actions.pendingRefs(this)
        val all = AdvRequest.list(advData?.optJSONArray("requests"))
        return if (pay) all.filter { it.status == AdvRequest.APPROVED && it.id !in waiting }
            else all.filter { it.canDecide && it.id !in waiting }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        if (loadingClaims || loadingAdv) body.addView(updatingBar(), lp())
        val claimsN = claims.size
        val advN = advList().size
        val claimsLabel = tr("Claims", "Claims") + (if (claimsN > 0) " ($claimsN)" else "")
        val advLabel = tr("Advance", "Advances") + (if (advN > 0) " ($advN)" else "")
        val sel = if (tab == TAB_CLAIMS) claimsLabel else advLabel
        body.addView(chips(listOf(claimsLabel, advLabel), sel) { pick ->
            tab = if (pick == advLabel) TAB_ADV else TAB_CLAIMS
            render()
        }, lp().margins(this, b = 12))

        if (tab == TAB_CLAIMS) renderClaims() else renderAdvances()
        actionBtn?.visibility = if (tab == TAB_CLAIMS && claims.isNotEmpty()) View.VISIBLE else View.GONE
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    private fun renderClaims() {
        val me = Session.user(this)
        if (!pay && me != null && !me.isAdmin && blocked.isNotEmpty()) {
            val limit = Settings.approvalLimit(this)
            body.addView(note(tr("${blocked.size} claim (${Fmt.rupees(limit)} से ऊपर या Manager के) सिर्फ Atul या Rahul approve करेंगे।",
                "${blocked.size} claims (above ${Fmt.rupees(limit)} or from managers) can be approved only by Atul or Rahul."), Ui.SUN_LIGHT, Ui.SUN_TEXT), lp().margins(this, b = 10))
        }
        val total = claims.sumOf { it.amount }
        body.addView(horizontal {
            addView(text("${claims.size} claim", 14f, Ui.MUTED, bold = true), lp(0, WRAP, 1f))
            addView(text(tr("कुल ", "Total ") + Fmt.rupees(total), 15f, Ui.TEXT, bold = true))
        }, lp().margins(this, b = 8))
        if (claims.isNotEmpty()) {
            val all = claims.all { it.id in selected }
            body.addView(text(if (all) tr("☑ सब हटाएँ", "☑ Clear all") else tr("☐ सब चुनें", "☐ Select all"), 14f, Ui.BLUE, bold = true) {
                setPadding(dp(4), dp(6), dp(4), dp(10))
                setOnClickListener { if (all) selected.removeAll(claims.map { it.id }.toSet()) else selected.addAll(claims.map { it.id }); render() }
            })
        }
        if (claims.isEmpty() && (pay || blocked.isEmpty()) && !loadingClaims) body.addView(note(if (pay) tr("कोई approved claim payment के लिए बाकी नहीं है।", "No approved claims waiting for payment.")
            else tr("कोई claim approval के लिए बाकी नहीं है।", "No claims waiting for approval.")))
        claims.forEach { c ->
            val row = claimRow(c, showMember = true, selected = c.id in selected) {
                startActivity(Intent(this, ClaimActivity::class.java).putExtra(ClaimActivity.EXTRA_ID, c.id))
            }
            row.setOnLongClickListener { toggle(c.id); true }
            (row as LinearLayout).getChildAt(0).let { line -> (line as LinearLayout).getChildAt(0).setOnClickListener { toggle(c.id) } }
            body.addView(row, lp().margins(this, b = 8))
        }
        // claims this approver may not act on: shown greyed out, tap to view
        if (!pay && blocked.isNotEmpty()) {
            body.addView(text(tr("सिर्फ Atul / Rahul approve करेंगे", "Only Atul or Rahul can approve"), 15f, Ui.MUTED, bold = true), lp().margins(this, t = 12, b = 8))
            blocked.forEach { c ->
                val row = claimRow(c, showMember = true, selected = null) {
                    startActivity(Intent(this, ClaimActivity::class.java).putExtra(ClaimActivity.EXTRA_ID, c.id))
                }
                row.alpha = 0.5f
                body.addView(row, lp().margins(this, b = 8))
            }
        }
        updateActionLabel()
    }

    private fun renderAdvances() {
        val list = advList()
        val total = list.sumOf { it.amount }
        body.addView(text(if (pay) tr("${list.size} advance approved, payment बाकी · कुल ${Fmt.rupees(total)}", "${list.size} approved, not paid · total ${Fmt.rupees(total)}")
            else tr("${list.size} request · कुल ${Fmt.rupees(total)}", "${list.size} requests · total ${Fmt.rupees(total)}"), 14f, Ui.MUTED, bold = true), lp().margins(this, b = 10))
        if (list.isEmpty() && !loadingAdv) body.addView(note(if (pay) tr("Payment के लिए कोई advance नहीं।", "No advances waiting for payment.")
            else tr("Approve करने के लिए कोई advance request नहीं।", "No advance requests to approve.")))
        list.forEach { r -> body.addView(advCard(r) { openAdv(r) }, lp().margins(this, b = 8)) }
    }

    private fun advCard(r: AdvRequest, onClick: () -> Unit): View = card(14) {
        isClickable = true
        foreground = context.ripple()
        addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(vertical {
                addView(text(r.member, 15.5f, Ui.TEXT, bold = true))
                addView(text(r.team + (if (r.neededBy.isNotBlank()) tr(" · चाहिए ", " · needed ") + Fmt.date(r.neededBy) else ""), 12f, Ui.MUTED))
            }, lp(0, WRAP, 1f))
            addView(text(Fmt.rupees(r.amount), 20f, Ui.TEXT, bold = true))
        })
        addView(text(r.purpose, 13.5f, Ui.TEXT), lp().margins(context, t = 4))
        addView(horizontal { addView(reqPill(r.status)) }, lp().margins(context, t = 8))
        setOnClickListener { onClick() }
    }

    private fun openAdv(r: AdvRequest) {
        startActivity(Intent(this, AdvanceRequestsActivity::class.java)
            .putExtra(AdvanceRequestsActivity.EXTRA_MODE, AdvanceRequestsActivity.MODE_DETAIL)
            .putExtra(AdvanceRequestsActivity.EXTRA_ID, r.id))
    }

    // ------------------------------------------------------------------ claim actions

    private fun updateActionLabel() {
        val btn = actionBtn as? LinearLayout ?: return
        val sum = claims.filter { it.id in selected }.sumOf { it.amount }
        btn.alpha = if (selected.isEmpty()) 0.45f else 1f
        (btn.getChildAt(btn.childCount - 1) as android.widget.TextView).text =
            (if (pay) tr("Paid mark करें", "Mark as paid") else tr("Approve करें", "Approve")) + if (selected.isEmpty()) "" else " · ${selected.size} · ${Fmt.rupees(sum)}"
    }

    private fun toggle(id: String) { if (!selected.remove(id)) selected.add(id); render() }

    private fun approveClaims() {
        if (selected.isEmpty()) { toast(tr("पहले claims चुनें (☐ पर tap करें)", "Select claims first (tap ☐)")); return }
        val ids = selected.toList()
        val sum = claims.filter { it.id in selected }.sumOf { it.amount }
        confirm(tr("Approve करें?", "Approve?"), tr("${ids.size} claim · कुल ${Fmt.rupees(sum)}", "${ids.size} claims · total ${Fmt.rupees(sum)}")) {
            sendOrQueue(tr("Approve हो रहा है…", "Approving…"), "decide", JSONObject().put("claimIds", JSONArray(ids)).put("approve", true),
                "Approve · ${ids.size} claim · ${Fmt.rupees(sum)}", ids) { r ->
                skippedOr(r) { if (!r.optBoolean("queued")) toast("${r.optInt("done")} claim approved"); selected.clear(); if (r.optBoolean("queued")) render() else loadClaims() }
            }
        }
    }

    private fun payClaims() {
        if (selected.isEmpty()) { toast(tr("पहले claims चुनें (☐ पर tap करें)", "Select claims first (tap ☐)")); return }
        payDialog(selected.toList(), claims.filter { it.id in selected }.sumOf { it.amount }) { selected.clear(); loadClaims() }
    }
}
