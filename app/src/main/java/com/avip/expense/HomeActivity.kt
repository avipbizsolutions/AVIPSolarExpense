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
    /** Open bike journeys, pinned under the top bar (does not scroll away). */
    private lateinit var journeyBar: LinearLayout
    private var swipe: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    private var user: User? = null
    private var filter = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        user = Session.user(this) ?: run { toLogin(); return }
        build()
        setupAlerts()
        if (Sync.pending(this)) Sync.schedule(this)
    }

    /** Everyone: allow notifications and start the background check (own claims; approvers also what waits for them). */
    private fun setupAlerts() {
        if (user == null) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        ApprovalAlerts.schedule(this)
        JourneyReminder.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        if (user == null) return
        render()
        refresh(soft = true)
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
                barButton(R.drawable.ic_logout) {
                    confirm("Logout?", tr("इस phone से logout करें?", "Log out from this phone?")) {
                        ApprovalAlerts.stop(this@HomeActivity); JourneyReminder.stop(this@HomeActivity); Session.clear(this@HomeActivity); Store.clear(this@HomeActivity); toLogin()
                    }
                },
            )))
            addView(loading, lp(MATCH, dp(4)))
            journeyBar = vertical { }
            addView(journeyBar, lp())
            addView(androidx.swiperefreshlayout.widget.SwipeRefreshLayout(context).apply {
                swipe = this
                setColorSchemeColors(Ui.BRAND)
                setOnRefreshListener { refresh() }
                addView(android.widget.ScrollView(context).apply { isFillViewport = true; addView(body) })
            }, lp(MATCH, 0, 1f))
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
    private fun refresh(soft: Boolean = false) {
        if (loadingNow) { swipe?.isRefreshing = false; return }
        // returning to the home screen within a few seconds: show cached data, don't re-fetch
        if (soft && Store.fetchedRecently("home")) { swipe?.isRefreshing = false; return }
        loadingNow = true
        loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Sync.now(this@HomeActivity)
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
                    Store.markFetched("home")
                    home.optJSONObject("user")?.let { Session.updateUser(this@HomeActivity, User.from(it)) }
                }
            }
            loadingNow = false
            swipe?.isRefreshing = false
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
            if (Sync.pending(this@HomeActivity)) runOnUiThread { if (!isFinishing) refresh() }
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
        // the "Advance with you" card is rendered just above "My claims" (see addAdvanceSection)

        // ---- role tiles
        runCatching {
        val counts = boot?.optJSONObject("counts") ?: JSONObject()
        if (u.canApprove || u.isAccounts) {
            val row = horizontal { }
            // Approvals: claims + advances, one screen (approvers)
            if (u.canApprove) row.addView(tile(tr("Approve करें", "Approvals"), counts.optInt("toApprove") + counts.optInt("advToApprove"), R.drawable.ic_check, Ui.PURPLE) {
                startActivity(Intent(this, InboxActivity::class.java).putExtra(InboxActivity.EXTRA_MODE, InboxActivity.MODE_APPROVE))
            }, lp(0, WRAP, 1f).margins(this, r = 8))
            // Payments: pay claims + pay advances, one screen (accounts)
            if (u.isAccounts) row.addView(tile(tr("Payment करें", "Payments"), counts.optInt("toPay") + counts.optInt("advToPay"), R.drawable.ic_rupee, Ui.GREEN) {
                startActivity(Intent(this, InboxActivity::class.java).putExtra(InboxActivity.EXTRA_MODE, InboxActivity.MODE_PAY))
            }, lp(0, WRAP, 1f).margins(this, r = 8))
            // Team advances: accounts see everyone; approvers see the team but not the founders' advances
            // Team: advances + all team claims, one screen with a toggle (accounts see everyone; approvers minus founders)
            if (u.isAccounts || u.canApprove) row.addView(tile(tr("Team का हिसाब", "Team records"), -1, R.drawable.ic_history, Ui.TEAL) {
                startActivity(Intent(this, TeamActivity::class.java))
            }, lp(0, WRAP, 1f))
            body.addView(row, lp().margins(this, b = 8))
        }
        }.onFailure { showError(it) }

        // ---- actions done without internet, waiting to be sent
        runCatching {
        val acts = Actions.all(this)
        if (acts.isNotEmpty()) {
            body.addView(card(14) {
                background = rounded(Ui.SUN_LIGHT, dpf(16f))
                val waiting = acts.filter { it.optString("error").isBlank() }
                if (waiting.isNotEmpty()) {
                    addView(text(tr("Internet आने पर अपने आप जाएगा (${waiting.size})", "Will be sent when the internet is back (${waiting.size})"), 14.5f, Ui.SUN_TEXT, bold = true))
                    waiting.take(6).forEach { addView(text("• " + it.optString("label"), 13f, Ui.TEXT), lp().margins(context, t = 2)) }
                }
                acts.filter { it.optString("error").isNotBlank() }.forEach { a ->
                    addView(horizontal {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text("✕ " + a.optString("label") + "\n" + a.optString("error"), 12.5f, Ui.DANGER), lp(0, WRAP, 1f))
                        addView(text("OK", 14f, Ui.BRAND, bold = true) {
                            setPadding(dp(12), dp(8), dp(4), dp(8))
                            setOnClickListener { Actions.remove(this@HomeActivity, a.optString("id")); render() }
                        })
                    }, lp().margins(context, t = 6))
                }
            }, lp().margins(this, b = 10))
        }
        }.onFailure { }

        // ---- bike journeys in progress: pinned under the top bar
        runCatching { renderJourneys() }.onFailure { journeyBar.removeAllViews() }

        // ---- advance & money card (just above My claims)
        runCatching { addAdvanceSection(boot, mine) }

        // ---- my claims
        val queued = runCatching { Outbox.asClaims(this) }.getOrDefault(emptyList())
        // a rejected claim being sent again is shown once, as "waiting to be sent"
        val queuedIds = queued.map { it.id }.toSet()
        val claims = queued + Claim.list(mine?.optJSONArray("claims")).filter { it.id !in queuedIds }
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
        shown.forEach { c -> runCatching { body.addView(claimRow(c, showMember = false) { open(c) }, lp().margins(this, b = 8)) } }
        body.addView(View(this), lp(MATCH, dp(20)))
    }

    /** One pinned row per open journey: since when, a big Finish button, and delete. */
    private fun renderJourneys() {
        journeyBar.removeAllViews()
        val drafts = Journeys.all(this)
        JourneyReminder.clearIfDone(this)
        if (drafts.isEmpty()) return
        val today = Fmt.today()
        drafts.forEach { j ->
            val t = j.optLong("startTime", 0L).takeIf { it > 0 } ?: j.optLong("savedAt")
            val sameDay = Fmt.isoDay(t) == today
            val since = java.text.SimpleDateFormat(if (sameDay) "hh:mm a" else "dd MMM, hh:mm a", java.util.Locale.ENGLISH).format(java.util.Date(t))
            val id = j.optString("id")
            val openIt = { startActivity(Intent(this, ClaimFormActivity::class.java).putExtra(ClaimFormActivity.EXTRA_JOURNEY, id)) }
            journeyBar.addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (sameDay) Ui.BRAND_LIGHT else Ui.SUN_LIGHT)
                setPadding(dp(16), dp(10), dp(8), dp(10))
                isClickable = true
                foreground = context.ripple()
                setOnClickListener { openIt() }
                addView(vertical {
                    addView(text(tr("🏍 Journey चल रही है · $since से", "🏍 Journey running since $since"), 14.5f, Ui.TEXT, bold = true) { maxLines = 1 })
                    val sub = listOf(tr("शुरू: ", "Start: ") + j.optString("startReading"), j.optString("description")).filter { it.isNotBlank() && it != tr("शुरू: ", "Start: ") }.joinToString(" · ")
                    addView(text(if (sameDay) sub else tr("अभी तक पूरी नहीं हुई · ", "Not finished yet · ") + sub, 12.5f, if (sameDay) Ui.MUTED else Ui.SUN_TEXT) { maxLines = 1 }, lp().margins(context, t = 1))
                }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton(tr("पूरी करें", "Finish"), Ui.SUN, Ui.TEXT, heightDp = 40) { openIt() })
                addView(icon(R.drawable.ic_delete, Ui.DANGER, 20).apply {
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    isClickable = true
                    contentDescription = tr("Draft हटाएँ", "Delete draft")
                    setOnClickListener { deleteDraft(id) }
                }, lp(dp(40), dp(40)).margins(context, l = 2))
            }, lp())
            journeyBar.addView(View(this).apply { setBackgroundColor(Ui.LINE) }, lp(MATCH, dp(1)))
        }
    }

    private fun deleteDraft(id: String) {
        confirm(tr("Draft हटाएँ?", "Delete draft?"), tr("यह Journey draft phone से हट जाएगा। यह वापस नहीं आएगा।", "This journey draft will be deleted from the phone. It can't be undone.")) {
            Journeys.remove(this, id); render()
        }
    }

    /** The "Advance with you" card, the Request-advance button, and any open advance requests. */
    private fun addAdvanceSection(boot: JSONObject?, mine: JSONObject?) {
        val bal = Balance.from(mine?.optJSONObject("balance") ?: boot?.optJSONObject("balance"))
        body.addView(card(18) {
            background = gradient(dpf(18f), Ui.BRAND_DARK, Ui.BRAND)
            addView(text(tr("Advance आपके पास", "Advance with you"), 13.5f, 0xCCFFFFFF.toInt()))
            addView(horizontal {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(Fmt.rupees(bal.advanceBalance), 32f, Color.WHITE, bold = true) { maxLines = 1 }, lp(0, WRAP, 1f))
                addView(pillButton(tr("Advance माँगें", "Request advance"), Color.WHITE, Ui.BRAND_DARK, heightDp = 38, iconRes = R.drawable.ic_plus) {
                    startActivity(Intent(this@HomeActivity, AdvanceRequestsActivity::class.java).putExtra(AdvanceRequestsActivity.EXTRA_MODE, AdvanceRequestsActivity.MODE_NEW))
                })
            }, lp().margins(context, t = 4))
            addView(text(tr("मिला ${Fmt.rupees(bal.advanceGiven)} · Claims में adjust ${Fmt.rupees(bal.advanceAdjusted)}", "Given ${Fmt.rupees(bal.advanceGiven)} · adjusted in claims ${Fmt.rupees(bal.advanceAdjusted)}") +
                if (bal.advancePending > 0) tr(" · Approval बाकी ${Fmt.rupees(bal.advancePending)}", " · pending approval ${Fmt.rupees(bal.advancePending)}") else "", 12.5f, 0xCCFFFFFF.toInt()),
                lp().margins(context, t = 2))
            addView(horizontal {
                addView(stat(tr("Approval बाकी", "Pending approval"), bal.pendingApproval, Ui.SUN), lp(0, WRAP, 1f))
                addView(stat(tr("Payment बाकी", "To be paid"), bal.toReimburse, 0xFF8EC5FF.toInt()), lp(0, WRAP, 1f))
                addView(stat("Paid", bal.reimbursed, 0xFF7BD8A6.toInt()), lp(0, WRAP, 1f))
            }, lp().margins(context, t = 16))
            setOnClickListener { startActivity(Intent(this@HomeActivity, AdvancesActivity::class.java)) }
        }, lp().margins(this, t = 4, b = 8))
        val queuedReqs = Actions.all(this).filter { it.optString("kind") == "requestAdvance" && it.optString("error").isBlank() }.map {
            val q = it.getJSONObject("body").getJSONObject("request")
            AdvRequest.from(JSONObject().put("id", q.optString("id")).put("amount", q.optDouble("amount")).put("purpose", q.optString("purpose")).put("status", AdvRequest.QUEUED))
        }
        (queuedReqs + AdvRequest.list(mine?.optJSONArray("advRequests")).filter { it.status == AdvRequest.PENDING || it.status == AdvRequest.APPROVED }).forEach { r ->
            body.addView(card(12) {
                addView(horizontal {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(vertical {
                        addView(text(tr("Advance request ", "Advance request ") + Fmt.rupees(r.amount), 14.5f, Ui.TEXT, bold = true))
                        addView(text(r.purpose, 12.5f, Ui.MUTED))
                    }, lp(0, WRAP, 1f))
                    addView(reqPill(r.status))
                })
                isClickable = true
                foreground = context.ripple()
                setOnClickListener { startActivity(Intent(this@HomeActivity, AdvancesActivity::class.java)) }
            }, lp().margins(this, b = 8))
        }
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
        if (c.odoGap != 0.0) add(tr("⚠ Odometer में ${Fmt.km(Math.abs(c.odoGap))} km का फर्क", "⚠ Odometer gap ${Fmt.km(Math.abs(c.odoGap))} km"))
        if (c.status == Status.REJECTED && c.remarks.isNotBlank()) add(tr("कारण: ", "Reason: ") + c.remarks)
    }
    if (extra.isNotEmpty()) addView(text(extra.joinToString(" · "), 12f, if (c.status == Status.REJECTED) Ui.DANGER else Ui.SUN_TEXT), lp().margins(context, t = 6))
    setOnClickListener { onClick() }
}
