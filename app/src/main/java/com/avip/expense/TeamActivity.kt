package com.avip.expense

import android.content.Intent
import android.graphics.Color
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
    private var claimStatus = ""     // claims status filter ("" = all)
    private var person = ""          // shared person drill-down (advances + claims)
    private var rangeFrom = ""       // shared date range (inclusive)
    private var rangeTo = ""
    private var preset = "30d"       // 30d | fy | all | custom
    private var loadingAdv = false
    private var loadingClaims = false

    private val PRESET_30 get() = tr("पिछले 30 दिन", "Last 30 days")
    private val PRESET_FY get() = tr("इस साल (FY)", "Current FY")
    private val PRESET_ALL get() = tr("सभी", "All")

    /** Indian financial year start (1 April) for the FY that today falls in, as yyyy-MM-dd. */
    private fun fyStart(): String {
        val c = java.util.Calendar.getInstance()
        val y = if (c.get(java.util.Calendar.MONTH) >= java.util.Calendar.APRIL) c.get(java.util.Calendar.YEAR) else c.get(java.util.Calendar.YEAR) - 1
        return "%04d-04-01".format(y)
    }

    private fun applyPreset(which: String) {
        preset = which
        rangeTo = Fmt.today()
        rangeFrom = when (which) {
            "fy" -> fyStart()
            "all" -> "2000-01-01"
            else -> Fmt.isoDay(System.currentTimeMillis() - 30L * 86400000)
        }
    }

    private fun presetLabel() = when (preset) { "30d" -> PRESET_30; "fy" -> PRESET_FY; "all" -> PRESET_ALL; else -> "" }

    /** [from 00:00, to 23:59] in millis, tolerant of from/to being swapped. */
    private fun rangeBounds(): Pair<Long, Long> {
        val a = Fmt.parseIso(rangeFrom) ?: 0L
        val b = Fmt.parseIso(rangeTo) ?: 0L
        return minOf(a, b) to (maxOf(a, b) + 86400000 - 1)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val u = Session.user(this) ?: run { finish(); return }
        if (!u.seesTeam) { finish(); return }
        applyPreset("30d")
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
            val next = if (pick == claimsLabel) TAB_CLAIMS else TAB_ADV
            if (next != tab) { tab = next; person = "" }
            render()
        }, lp().margins(this, b = 10))
        dateSection()
        if (tab == TAB_ADV) renderAdvances() else renderClaims()
        if (!loadingAdv && !loadingClaims) swipe?.isRefreshing = false
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    /** Shared From/To date buttons and preset chips, used by both tabs. */
    private fun dateSection() {
        body.addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(dateBtn(tr("से", "From"), rangeFrom) { pickDate(rangeFrom.ifBlank { Fmt.today() }) { d -> rangeFrom = d; preset = "custom"; render() } }, lp(0, WRAP, 1f).margins(context, r = 8))
            addView(dateBtn(tr("तक", "To"), rangeTo) { pickDate(rangeTo.ifBlank { Fmt.today() }) { d -> rangeTo = d; preset = "custom"; render() } }, lp(0, WRAP, 1f))
        }, lp().margins(this, b = 8))
        body.addView(chips(listOf(PRESET_30, PRESET_FY, PRESET_ALL), presetLabel()) { pick ->
            applyPreset(when (pick) { PRESET_FY -> "fy"; PRESET_ALL -> "all"; else -> "30d" }); render()
        }, lp().margins(this, b = 12))
    }

    private fun renderAdvances() {
        val o = advData ?: return
        val (lo, hi) = rangeBounds()
        val people = (0 until (o.optJSONArray("people")?.length() ?: 0)).map { Balance.from(o.optJSONArray("people")!!.getJSONObject(it)) }
        val balByName = people.associateBy({ it.name.lowercase() }, { it.advanceBalance })
        val inRange = Advance.list(o.optJSONArray("advances")).filter { val t = Fmt.parseIso(it.date); t == null || t in lo..hi }

        // ---- team total
        body.addView(card(16) {
            addView(text(tr("Team को दिए Advance", "Advances given (team)"), 13f, Ui.MUTED))
            addView(text(Fmt.rupees(inRange.sumOf { it.amount }), 26f, Ui.TEXT, bold = true))
            addView(text("${inRange.size} advance · " + tr("अभी team के पास ", "with team now ") + Fmt.rupees(people.sumOf { it.advanceBalance }), 13f, Ui.MUTED), lp().margins(context, t = 4))
        }, lp().margins(this, b = 12))

        // ---- per person (in range; tap to drill)
        body.addView(text(tr("व्यक्ति पर tap करें = उसके advance", "Tap a person to see their advances"), 12.5f, Ui.MUTED), lp().margins(this, b = 8))
        inRange.groupBy { it.member.ifBlank { "—" } }.entries.sortedByDescending { e -> e.value.sumOf { it.amount } }.forEach { (name, list) ->
            val bal = balByName[name.lowercase()]
            body.addView(card(14) {
                isClickable = true
                foreground = context.ripple()
                if (person == name) background = rounded(0xFFFFF8E8.toInt(), dpf(16f), Ui.SUN, dp(2))
                addView(horizontal {
                    addView(vertical {
                        addView(text(name, 15f, Ui.TEXT, bold = true))
                        addView(text("${list.size} advance" + (if (bal != null) tr(" · पास में ${Fmt.rupees(bal)}", " · with them ${Fmt.rupees(bal)}") else ""), 12.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(Fmt.rupees(list.sumOf { it.amount }), 17f, Ui.TEXT, bold = true))
                })
                setOnClickListener { person = if (person == name) "" else name; render() }
            }, lp().margins(this, b = 8))
        }

        // ---- advance history (filtered by selected person)
        val shown = inRange.filter { person.isBlank() || it.member == person }
        body.addView(horizontal {
            addView(text(if (person.isBlank()) tr("सारे Advance", "All advances") else tr("$person के Advance", "$person's advances"), 17f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            addView(text(Fmt.rupees(shown.sumOf { it.amount }), 15f, Ui.TEXT, bold = true))
        }, lp().margins(this, t = 8, b = 8))
        if (shown.isEmpty() && !loadingAdv) body.addView(note(tr("इस दौरान कोई advance नहीं।", "No advances in this period.")))
        shown.forEach { a ->
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
        val all = Claim.list(Store.load(this, "team")?.optJSONArray("claims"))
        val (lo, hi) = rangeBounds()
        // ---- status filter
        body.addView(chips(listOf("All", Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).map { if (it == "All") it else Status.label(it) },
            if (claimStatus.isBlank()) "All" else Status.label(claimStatus)) { pick ->
            claimStatus = listOf(Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).firstOrNull { Status.label(it) == pick } ?: ""
            render()
        }, lp().margins(this, b = 10))

        val inRange = all.filter { val t = Fmt.parseIso(it.date); t == null || t in lo..hi }
            .filter { claimStatus.isBlank() || it.status == claimStatus }

        // ---- team total (all users)
        val paid = inRange.filter { it.status == Status.REIMBURSED }.sumOf { it.amount }
        val pending = inRange.filter { it.status == Status.PENDING }.sumOf { it.amount }
        body.addView(card(16) {
            addView(text(tr("Team के कुल Claims", "Total claims (team)"), 13f, Ui.MUTED))
            addView(text(Fmt.rupees(inRange.sumOf { it.amount }), 26f, Ui.TEXT, bold = true))
            addView(text("${inRange.size} claim · " + tr("Pending ", "Pending ") + Fmt.rupees(pending) + tr(" · मिला ", " · paid ") + Fmt.rupees(paid), 13f, Ui.MUTED), lp().margins(context, t = 4))
        }, lp().margins(this, b = 12))

        // ---- per person (tap to drill)
        body.addView(text(tr("व्यक्ति पर tap करें = उसके claims", "Tap a person to see their claims"), 12.5f, Ui.MUTED), lp().margins(this, b = 8))
        inRange.groupBy { it.member.ifBlank { "—" } }.entries.sortedByDescending { e -> e.value.sumOf { it.amount } }.forEach { (name, list) ->
            body.addView(card(14) {
                isClickable = true
                foreground = context.ripple()
                if (person == name) background = rounded(0xFFFFF8E8.toInt(), dpf(16f), Ui.SUN, dp(2))
                addView(horizontal {
                    addView(vertical {
                        addView(text(name, 15f, Ui.TEXT, bold = true))
                        addView(text("${list.size} claim", 12.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(text(Fmt.rupees(list.sumOf { it.amount }), 17f, Ui.TEXT, bold = true))
                })
                setOnClickListener { person = if (person == name) "" else name; render() }
            }, lp().margins(this, b = 8))
        }

        // ---- claim list (filtered by selected person)
        val shown = inRange.filter { person.isBlank() || it.member == person }
        body.addView(horizontal {
            addView(text(if (person.isBlank()) tr("सारे Claims", "All claims") else tr("$person के Claims", "$person's claims"), 17f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            addView(text(Fmt.rupees(shown.sumOf { it.amount }), 15f, Ui.TEXT, bold = true))
        }, lp().margins(this, t = 8, b = 8))
        if (shown.isEmpty() && !loadingClaims) body.addView(note(tr("इस दौरान कोई claim नहीं।", "No claims in this period.")))
        shown.forEach { c ->
            body.addView(claimRow(c, showMember = true, selected = null) {
                startActivity(Intent(this, ClaimActivity::class.java).putExtra(ClaimActivity.EXTRA_ID, c.id))
            }, lp().margins(this, b = 8))
        }
    }

    private fun dateBtn(label: String, iso: String, onClick: () -> Unit): View = vertical(10) {
        background = rounded(Color.WHITE, dpf(12f), 0xFFC8BDD8.toInt(), dp(1))
        isClickable = true
        foreground = context.ripple()
        addView(text(label, 11.5f, Ui.MUTED))
        addView(text(if (iso.isBlank()) "—" else Fmt.date(iso), 14.5f, Ui.TEXT, bold = true))
        setOnClickListener { onClick() }
    }
}
