package com.avip.expense

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Home: the person's advance balance and claim totals at the top (seen straight
 * after login), a big "New claim" button, approver / accounts tiles by role,
 * and the person's own claims.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var loading: ProgressBar
    private var user: User? = null
    private var filter = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        user = Session.user(this) ?: run { toLogin(); return }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (user == null) return
        render()
        refresh()
    }

    private fun build() {
        val u = user!!
        body = vertical(16)
        loading = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            visibility = View.GONE
        }
        val root = vertical {
            setBackgroundColor(Ui.BG)
            addView(topBar("AVIP Solar Expense", u.name + " · " + Role.label(u.role), showBack = false, right = listOf(
                langToggle(),
                barButton(R.drawable.ic_refresh) { refresh() },
                barButton(R.drawable.ic_logout) {
                    confirm("Logout?", tr("इस phone से logout करें?", "Log out from this phone?")) {
                        Session.clear(this@HomeActivity); Store.clear(this@HomeActivity); toLogin()
                    }
                },
            )))
            addView(loading, lp(MATCH, dp(4)))
            addView(android.widget.ScrollView(context).apply { isFillViewport = true; addView(body) }, lp(MATCH, 0, 1f))
            addView(vertical {
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(10), dp(16), dp(14))
                elevation = dpf(8f)
                addView(horizontal {
                    addView(pillButton(tr("Journey शुरू", "Start journey"), Ui.BRAND, heightDp = 54, iconRes = R.drawable.ic_location) {
                        startActivity(Intent(this@HomeActivity, ClaimFormActivity::class.java).putExtra(ClaimFormActivity.EXTRA_JOURNEY, "new"))
                    }, lp(0, WRAP, 1f).margins(context, r = 8))
                    addView(pillButton(tr("नया Claim", "New claim"), Ui.SUN, Ui.TEXT, heightDp = 54, iconRes = R.drawable.ic_plus) {
                        startActivity(Intent(this@HomeActivity, ClaimFormActivity::class.java))
                    }, lp(0, WRAP, 1f))
                }, lp())
            })
        }
        setContentView(root)
    }

    private var loadingNow = false

    /** Sends any claims waiting on the phone first, then loads everything in one request. */
    private fun refresh() {
        if (loadingNow) return
        loadingNow = true
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Outbox.sendAll(this@HomeActivity)
                    val home = try {
                        Api.call(this@HomeActivity, "home")
                    } catch (e: ApiException) {
                        // office script not updated yet: the two older requests
                        if (!e.message.orEmpty().contains("needs updating")) throw e
                        Api.call(this@HomeActivity, "bootstrap").also { b ->
                            val m = Api.call(this@HomeActivity, "myClaims")
                            b.put("claims", m.optJSONArray("claims")).put("advances", m.optJSONArray("advances")).put("balance", m.optJSONObject("balance"))
                        }
                    }
                    Store.save(this@HomeActivity, "boot", home)
                    Store.save(this@HomeActivity, "mine", home)
                    home.optJSONObject("user")?.let { Session.updateUser(this@HomeActivity, User.from(it)) }
                }
            }
            loadingNow = false
            if (isFinishing) return@launch
            loading.visibility = View.GONE
            r.onFailure { e ->
                if (e is ApiException && e.sessionExpired) { showError(e); return@launch }
                toast(e.message ?: "Could not refresh")
            }
            user = Session.user(this@HomeActivity) ?: user
            render()
        }
    }

    /** When the internet comes back while the app is open, waiting claims go and the list updates. */
    private val netCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (Outbox.all(this@HomeActivity).isNotEmpty()) runOnUiThread { if (!isFinishing) refresh() }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).registerDefaultNetworkCallback(netCallback)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            (getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(netCallback)
        } catch (_: Exception) {}
    }

    private fun render() {
        val u = user ?: return
        body.removeAllViews()
        val boot = Store.load(this, "boot")
        val mine = Store.load(this, "mine")
        val bal = Balance.from(mine?.optJSONObject("balance") ?: boot?.optJSONObject("balance"))

        // ---- advance & money card
        body.addView(card(18) {
            background = gradient(dpf(18f), Ui.BRAND_DARK, Ui.BRAND)
            addView(text(tr("Advance आपके पास", "Advance with you"), 13.5f, 0xCCFFFFFF.toInt()))
            addView(text(Fmt.rupees(bal.advanceBalance), 32f, Color.WHITE, bold = true), lp().margins(context, t = 2))
            addView(text(tr("मिला ${Fmt.rupees(bal.advanceGiven)} · Claims में adjust ${Fmt.rupees(bal.advanceAdjusted)}", "Given ${Fmt.rupees(bal.advanceGiven)} · adjusted in claims ${Fmt.rupees(bal.advanceAdjusted)}") +
                if (bal.advancePending > 0) tr(" · Approval बाकी ${Fmt.rupees(bal.advancePending)}", " · pending approval ${Fmt.rupees(bal.advancePending)}") else "", 12.5f, 0xCCFFFFFF.toInt()),
                lp().margins(context, t = 2))
            addView(horizontal {
                addView(stat(tr("Approval बाकी", "Pending approval"), bal.pendingApproval, Ui.SUN), lp(0, WRAP, 1f))
                addView(stat(tr("Payment बाकी", "To be paid"), bal.toReimburse, 0xFF8EC5FF.toInt()), lp(0, WRAP, 1f))
                addView(stat("Paid", bal.reimbursed, 0xFF7BD8A6.toInt()), lp(0, WRAP, 1f))
            }, lp().margins(context, t = 16))
            setOnClickListener { startActivity(Intent(this@HomeActivity, AdvancesActivity::class.java)) }
        }, lp().margins(this, b = 6))
        body.addView(text(tr("Advance की पूरी list देखने के लिए ऊपर tap करें", "Tap above to see all your advances"), 12f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, b = 12))

        // ---- role tiles
        val counts = boot?.optJSONObject("counts") ?: JSONObject()
        if (u.canApprove || u.isAccounts) {
            val row = horizontal { }
            if (u.canApprove) row.addView(tile(tr("Claims Approve करें", "Approve claims"), counts.optInt("toApprove"), R.drawable.ic_check, Ui.PURPLE) {
                startActivity(Intent(this, TeamClaimsActivity::class.java).putExtra(TeamClaimsActivity.EXTRA_MODE, TeamClaimsActivity.MODE_APPROVE))
            }, lp(0, WRAP, 1f).margins(this, r = 8))
            if (u.isAccounts) row.addView(tile(tr("Payment करें", "Pay claims"), counts.optInt("toPay"), R.drawable.ic_rupee, Ui.GREEN) {
                startActivity(Intent(this, TeamClaimsActivity::class.java).putExtra(TeamClaimsActivity.EXTRA_MODE, TeamClaimsActivity.MODE_PAY))
            }, lp(0, WRAP, 1f).margins(this, r = 8))
            if (u.isAccounts) row.addView(tile(tr("Advance दें", "Give advance"), -1, R.drawable.ic_person, Ui.TEAL) {
                startActivity(Intent(this, AdvancesActivity::class.java).putExtra(AdvancesActivity.EXTRA_TEAM, true))
            }, lp(0, WRAP, 1f))
            body.addView(row, lp().margins(this, b = 8))
            body.addView(text(tr("Team के सारे Claims", "All team claims"), 14f, Ui.BLUE, bold = true) {
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener {
                    startActivity(Intent(this@HomeActivity, TeamClaimsActivity::class.java).putExtra(TeamClaimsActivity.EXTRA_MODE, TeamClaimsActivity.MODE_ALL))
                }
            }, lp().margins(this, b = 8))
        }

        // ---- bike journeys in progress (drafts on this phone)
        val drafts = Journeys.all(this)
        if (drafts.isNotEmpty()) {
            body.addView(text(tr("चल रही Journey (Draft)", "Journeys in progress (drafts)"), 18f, Ui.TEXT, bold = true), lp().margins(this, t = 4, b = 8))
            drafts.forEach { j ->
                val t = j.optLong("startTime", 0L).takeIf { it > 0 } ?: j.optLong("savedAt")
                val whenTxt = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date(t))
                body.addView(card(14) {
                    background = rounded(Ui.BRAND_LIGHT, dpf(16f), Ui.BRAND, dp(1))
                    isClickable = true
                    foreground = context.ripple()
                    addView(text(tr("Journey शुरू: $whenTxt", "Started: $whenTxt"), 15f, Ui.TEXT, bold = true))
                    addView(text(tr("शुरू का odometer: ", "Start odometer: ") + j.optString("startReading") +
                        (if (j.optString("description").isNotBlank()) " · " + j.optString("description") else ""), 13.5f, Ui.MUTED), lp().margins(context, t = 2))
                    addView(text(tr("लौटने पर tap करें → आखिर के odometer की photo → Claim भेजें", "Tap when you are back → end odometer photo → send claim"), 13f, Ui.BRAND, bold = true), lp().margins(context, t = 6))
                    setOnClickListener {
                        startActivity(Intent(this@HomeActivity, ClaimFormActivity::class.java).putExtra(ClaimFormActivity.EXTRA_JOURNEY, j.optString("id")))
                    }
                    setOnLongClickListener {
                        confirm(tr("Draft हटाएँ?", "Delete draft?"), tr("यह Journey draft phone से हट जाएगा।", "This journey draft will be deleted from the phone.")) {
                            Journeys.remove(this@HomeActivity, j.optString("id")); render()
                        }
                        true
                    }
                }, lp().margins(this, b = 8))
            }
        }

        // ---- my claims
        val queued = Outbox.asClaims(this)
        val claims = queued + Claim.list(mine?.optJSONArray("claims"))
        body.addView(horizontal {
            addView(text(tr("मेरे Claims", "My claims"), 18f, Ui.TEXT, bold = true), lp(0, WRAP, 1f))
            addView(text("${claims.size}", 13f, Ui.MUTED))
        }, lp().margins(this, t = 4, b = 8))
        body.addView(chips(listOf("All", Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).map { if (it == "All") it else Status.label(it) },
            if (filter == "All") "All" else Status.label(filter)) { pick ->
            filter = listOf(Status.PENDING, Status.APPROVED, Status.REIMBURSED, Status.REJECTED).firstOrNull { Status.label(it) == pick } ?: "All"
            render()
        }, lp().margins(this, b = 10))

        val shown = claims.filter { filter == "All" || it.status == filter }
        if (shown.isEmpty()) {
            body.addView(note(if (claims.isEmpty()) tr("अभी कोई Claim नहीं है। नीचे \"नया Claim डालें\" दबाएँ।", "No claims yet. Tap \"Add new claim\" below.") else tr("इस filter में कोई Claim नहीं।", "No claims in this filter.")))
        }
        shown.forEach { c -> body.addView(claimRow(c, showMember = false) { open(c) }, lp().margins(this, b = 8)) }
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    private fun stat(label: String, value: Double, color: Int): View = vertical {
        addView(text(Fmt.rupees(value), 16f, color, bold = true))
        addView(text(label, 11.5f, 0xCCFFFFFF.toInt()))
    }

    private fun tile(label: String, count: Int, iconRes: Int, color: Int, onClick: () -> Unit): View = card(12) {
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        foreground = context.ripple()
        addView(android.widget.FrameLayout(context).apply {
            addView(badge(iconRes, color, 44, square = true))
            if (count > 0) addView(text(count.toString(), 11f, Color.WHITE, bold = true) {
                background = circle(Ui.DANGER)
                gravity = Gravity.CENTER
                minWidth = dp(20); minHeight = dp(20)
            }, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP))
        }, lp(dp(52), dp(50)))
        addView(text(label, 12.5f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER; setLines(2) }, lp().margins(context, t = 6))
        setOnClickListener { onClick() }
    }

    private fun open(c: Claim) {
        startActivity(Intent(this, ClaimActivity::class.java).putExtra(ClaimActivity.EXTRA_ID, c.id))
    }

    private fun toLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}

/** One claim in a list: category, date, amount, status. */
fun android.content.Context.claimRow(c: Claim, showMember: Boolean, selected: Boolean? = null, onClick: () -> Unit): View = card(14) {
    isClickable = true
    foreground = context.ripple()
    if (selected == true) background = rounded(0xFFFFF8E8.toInt(), dpf(16f), Ui.SUN, dp(2))
    addView(horizontal {
        if (selected != null) addView(text(if (selected) "☑" else "☐", 22f, if (selected) Ui.SUN_TEXT else Ui.MUTED), lp(WRAP, WRAP).margins(context, r = 10))
        addView(vertical {
            if (showMember) addView(text(c.member.ifBlank { "—" }, 15f, Ui.TEXT, bold = true) { maxLines = 1 })
            addView(text(c.category, if (showMember) 13.5f else 15f, if (showMember) Ui.MUTED else Ui.TEXT, bold = !showMember) { maxLines = 1 })
            addView(text(Fmt.date(c.date) + (if (c.description.isNotBlank()) " · " + c.description else ""), 12.5f, Ui.MUTED) { maxLines = 1 })
        }, lp(0, WRAP, 1f))
        addView(vertical {
            gravity = Gravity.END
            addView(text(Fmt.rupees(c.amount), 17f, Ui.TEXT, bold = true))
            addView(statusPill(c.status), lp(WRAP, WRAP).margins(context, t = 4))
        })
    })
    val extra = buildList {
        if (c.paidBy == "Advance") add(tr("Advance से", "From advance"))
        if (c.paidBy == "Company") add(tr("Company ने दिया", "Paid by company"))
        if (!c.hasPhoto) add(tr("Bill photo नहीं", "No bill photo"))
        if (c.status == Status.REJECTED && c.remarks.isNotBlank()) add(tr("कारण: ", "Reason: ") + c.remarks)
    }
    if (extra.isNotEmpty()) addView(text(extra.joinToString(" · "), 12f, if (c.status == Status.REJECTED) Ui.DANGER else Ui.SUN_TEXT), lp().margins(context, t = 6))
    setOnClickListener { onClick() }
}
