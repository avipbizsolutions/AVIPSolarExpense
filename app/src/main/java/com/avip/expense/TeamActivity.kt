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
import org.json.JSONObject

/**
 * Team records in one screen, with an Advances / Claims toggle:
 *  - Advances: each person's advance balance and the advance history (team view).
 *  - Claims: all team claims, filtered by status and person.
 * Read-only; acting on a claim happens in ClaimActivity. Approvers see the team's
 * advances but not the founders' (enforced by the office script).
 */
class TeamActivity : AppCompatActivity() {

    companion object {
        private const val TAB_ADV = "advances"
        private const val TAB_CLAIMS = "claims"
    }

    private lateinit var body: LinearLayout
    private var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var tab = TAB_ADV
    private var advData: JSONObject? = null
    private var person = ""          // advances drill-down
    private var claimStatus = ""     // claims status filter ("" = all)
    private var claimPerson = ""     // claims person filter
    private var loadingAdv = false
    private var loadingClaims = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val u = Session.user(this) ?: run { finish(); return }
        if (!u.seesTeam) { finish(); return }
        body = screen(tr("Team का हिसाब", "Team records"), tr("Advances और Claims, एक जगह", "Advances and claims in one place"),
            right = listOf(barButton(R.drawable.ic_refresh) { loadAll() }),
            onRefresh = { loadAll() }, onSwipe = { swipe = it })
    }

    override fun onResume() {
        super.onResume()
        loadAll()
    }

    private fun loadAll() { loadAdvances(); loadClaims() }

    // ------------------------------------------------------------------ loaders

    private fun loadAdvances() {
        if (loadingAdv) { swipe?.isRefreshing = false; return }
        loadingAdv = true
        if (advData == null) advData = Store.load(this, "adv_team")
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.call(this@TeamActivity, "advances", JSONObject().put("team", true)) } }
            loadingAdv = false
            swipe?.isRefreshing = false
            if (isFinishing) return@launch
            r.onSuccess { advData = it; Store.save(this@TeamActivity, "adv_team", it) }
            render()
            r.onFailure { showError(it) }
        }
    }

    private fun loadClaims() {
        if (loadingClaims) { swipe?.isRefreshing = false; return }
        loadingClaims = true
        render()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Api.call(this@TeamActivity, "teamClaims", JSONObject().put("status", "")) } }
            loadingClaims = false
            swipe?.isRefreshing = false
            if (isFinishing) return@launch
            r.onSuccess { Store.save(this@TeamActivity, "team", JSONObject().put("claims", it.optJSONArray("claims"))) }
            render()
            r.onFailure { showError(it) }
        }
    }

    // ------------------------------------------------------------------ render

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        if (loadingAdv || loadingClaims) body.addView(updatingBar(), lp())
        val advLabel = tr("Advance", "Advances")
        val claimsLabel = tr("Claims", "Claims")
        body.addView(chips(listOf(advLabel, claimsLabel), if (tab == TAB_ADV) advLabel else claimsLabel) { pick ->
            tab = if (pick == claimsLabel) TAB_CLAIMS else TAB_ADV
            render()
        }, lp().margins(this, b = 12))
        if (tab == TAB_ADV) renderAdvances() else renderClaims()
        if (!loadingAdv && !loadingClaims) swipe?.isRefreshing = false
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    private fun renderAdvances() {
        val o = advData ?: return
        val people = o.optJSONArray("people")
        if (people != null) {
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
        }
        val advs = Advance.list(o.optJSONArray("advances")).filter { person.isBlank() || it.member == person }
        body.addView(text(if (person.isBlank()) "Advance history" else tr("$person के Advance", "Advances of $person"), 17f, Ui.TEXT, bold = true), lp().margins(this, t = 8, b = 8))
        if (advs.isEmpty() && !loadingAdv) body.addView(note(tr("अभी कोई Advance नहीं।", "No advances yet.")))
        advs.forEach { a ->
            body.addView(card(14) {
                addView(horizontal {
                    addView(vertical {
                        addView(text(a.member, 15f, Ui.TEXT, bold = true))
                        addView(text(Fmt.date(a.date) + " · " + a.via + if (a.ref.isNotBlank()) " · " + a.ref else "", 12.5f, Ui.MUTED))
                        if (a.purpose.isNotBlank()) addView(text(a.purpose, 13.5f, Ui.TEXT))
                        addView(text(a.id + " · " + a.givenBy, 11.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(Fmt.rupees(a.amount), 17f, Ui.GREEN, bold = true))
                })
            }, lp().margins(this, b = 8))
        }
    }

    private fun renderClaims() {
        val claims = Claim.list(Store.load(this, "team")?.optJSONArray("claims"))
        val people = claims.map { it.member }.filter { it.isNotBlank() }.distinct().sorted()
        body.addView(chips(listOf("All", Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).map { if (it == "All") it else Status.label(it) },
            if (claimStatus.isBlank()) "All" else Status.label(claimStatus)) { pick ->
            claimStatus = listOf(Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).firstOrNull { Status.label(it) == pick } ?: ""
            render()
        }, lp().margins(this, b = 8))
        if (people.size > 1) body.addView(chips(people, claimPerson) { claimPerson = if (claimPerson == it) "" else it; render() }, lp().margins(this, b = 10))
        val shown = claims.filter { (claimStatus.isBlank() || it.status == claimStatus) && (claimPerson.isBlank() || it.member == claimPerson) }
        body.addView(horizontal {
            addView(text("${shown.size} claim", 14f, Ui.MUTED, bold = true), lp(0, WRAP, 1f))
            addView(text(tr("कुल ", "Total ") + Fmt.rupees(shown.sumOf { it.amount }), 15f, Ui.TEXT, bold = true))
        }, lp().margins(this, b = 8))
        if (shown.isEmpty() && !loadingClaims) body.addView(note(tr("कोई claim नहीं।", "No claims.")))
        shown.forEach { c ->
            body.addView(claimRow(c, showMember = true, selected = null) {
                startActivity(Intent(this, ClaimActivity::class.java).putExtra(ClaimActivity.EXTRA_ID, c.id))
            }, lp().margins(this, b = 8))
        }
    }
}
