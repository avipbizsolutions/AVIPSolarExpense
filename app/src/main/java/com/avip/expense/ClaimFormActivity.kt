package com.avip.expense

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * New expense claim. Saved on the phone first (so nothing is lost without
 * signal), then sent to the office with the bill photo.
 */
class ClaimFormActivity : AppCompatActivity() {

    companion object {
        /** "new" = start a bike journey; otherwise the id of a journey draft to finish. */
        const val EXTRA_JOURNEY = "journey"
        /** Id of the person's own rejected claim, to correct and send again. */
        const val EXTRA_FIX = "fix"
    }

    private var date = Fmt.today()
    private var category = ""
    private var paidBy = "Advance"
    private var paymentMode = "Cash"
    private val photos = mutableListOf<String>()

    /** Fuel / Petrol: start and end odometer photo + reading. */
    private inner class Odo(val key: String, val title: String) {
        var photo = ""
        var time: Long? = null
        var stamp = "" 
        var auto: Long? = null
        lateinit var box: LinearLayout
        lateinit var reading: EditText
        lateinit var hint: android.widget.TextView
        lateinit var alts: LinearLayout
        lateinit var cap: android.widget.TextView
    }
    private val odoStart = Odo("start", "")
    private val odoEnd = Odo("end", "")
    private var odoTarget: Odo? = null
    private var fuelRate = Settings.DEFAULT_FUEL_RATE
    /** Fuel claim: "Own" bike (₹ per km) or "Company" bike (petrol bill as a lump sum). */
    private var bike = "Own"
    private lateinit var bikeChips: LinearLayout
    private lateinit var companyBox: View
    private lateinit var bikeNo: EditText
    private lateinit var petrol: EditText
    private lateinit var fuelHint: android.widget.TextView
    private lateinit var billSection: View
    private lateinit var fuelBox: View
    private lateinit var fuelSum: android.widget.TextView
    private lateinit var amountBox: View

    private lateinit var body: LinearLayout
    private lateinit var dateBtn: android.widget.TextView
    private lateinit var catBtn: android.widget.TextView
    private var notice: android.widget.TextView? = null
    private lateinit var amount: EditText
    private lateinit var desc: EditText
    private lateinit var site: EditText
    private lateinit var district: EditText
    private lateinit var km: EditText
    private lateinit var billNo: EditText
    private lateinit var kmBox: View
    private lateinit var advanceNote: android.widget.TextView
    private lateinit var photoBox: LinearLayout

    private var claimId = ""
    private var journey: JSONObject? = null
    /** Opened from "Start journey" (or one of its drafts): odometer photos only from the GPS camera. */
    private var journeyMode = false
    /** Step 1 of a bike journey: only the start odometer is asked; everything else comes at the end. */
    private var starting = false
    private lateinit var draftBtn: View
    /** A rejected claim being corrected: its photos are already with the office and stay unless retaken. */
    private var fixClaim: Claim? = null
    private var keepStart = false
    private var keepEnd = false
    private var remoteBills = 0
    private lateinit var dateNote: android.widget.TextView

    private fun newClaimId(): String {
        val u = Session.user(this)
        return "CLM-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date()) + "-" +
            (u?.id ?: "X").uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = r.data?.getStringArrayListExtra(CameraActivity.EXTRA_FILES).orEmpty().lastOrNull()
        val odo = odoTarget
        if (f != null) { if (odo != null) setOdoPhoto(odo, f) else { photos.add(f); showPhoto() } }
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val odo = odoTarget
        if (uri != null) {
            val f = Photos.importUri(this, uri, claimId + (odo?.let { "_odo_" + it.key } ?: "_g") + System.currentTimeMillis() % 100000)
            if (f == null) toast(tr("यह photo नहीं खुल सकी", "Could not open this photo"))
            else { photos.add(f.absolutePath); showPhoto() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val jx = intent.getStringExtra(EXTRA_JOURNEY)
        journeyMode = jx != null
        starting = jx == "new"
        journey = if (jx != null && jx != "new") Journeys.get(this, jx) else null
        val fx = intent.getStringExtra(EXTRA_FIX)
        if (fx != null) {
            fixClaim = Claim.list(Store.load(this, "mine")?.optJSONArray("claims")).firstOrNull { it.id == fx && it.status == Status.REJECTED }
            if (fixClaim == null) { finish(); return }
        }
        claimId = fixClaim?.id ?: journey?.optString("id")?.takeIf { it.isNotBlank() } ?: newClaimId()
        val boot = Store.load(this, "boot")
        val categories = Store.strings(boot, "categories", Store.DEFAULT_CATEGORIES)
        val modes = Store.strings(boot, "paymentModes", Store.DEFAULT_MODES)
        val bal = Balance.from(Store.load(this, "mine")?.optJSONObject("balance") ?: boot?.optJSONObject("balance"))
        fuelRate = Settings.fuelRate(this)
        // nothing left in the advance: the claim can only be the rider's own money
        fixClaim?.let { f ->
            paidBy = if (f.paidBy == "Advance") "Advance" else "Self"
            paymentMode = f.paymentMode.takeIf { it in modes } ?: "Cash"
        }
        if (bal.advanceBalance <= 0) paidBy = "Self"

        body = when {
            fixClaim != null -> screen(tr("ठीक करके फिर भेजें", "Fix and resend"), fixClaim!!.category + " · " + Fmt.date(fixClaim!!.date),
                bottom = pillButton(tr("फिर से भेजें", "Send again"), Ui.SUN, Ui.TEXT, heightDp = 54) { submit() })
            starting -> screen(tr("Journey शुरू करें", "Start journey"), tr("Step 1 / 2 · शुरू के odometer की photo", "Step 1 of 2 · start odometer photo"),
                bottom = pillButton(tr("Journey शुरू करें", "Start journey"), Ui.BRAND, heightDp = 54, iconRes = R.drawable.ic_location) { saveDraft() })
            journeyMode -> screen(tr("Journey पूरी करें", "Finish journey"), tr("Step 2 / 2 · आखिर की photo → Claim भेजें", "Step 2 of 2 · end odometer photo → send claim"),
                bottom = pillButton(tr("Claim भेजें", "Send claim"), Ui.SUN, Ui.TEXT, heightDp = 54) { submit() })
            else -> screen(tr("नया Claim", "New claim"), tr("एक bill = एक claim", "One bill = one claim"), bottom = pillButton(tr("Claim भेजें", "Send claim"), Ui.SUN, Ui.TEXT, heightDp = 54) { submit() })
        }
        if (starting) body.addView(note(tr("① अभी odometer की photo लें और reading भरें।\n② \"Journey शुरू करें\" दबाएँ।\n③ लौटने पर Home से यही journey खोलें, आखिर की photo लें और claim भेजें।",
            "① Take a photo of your odometer now and enter the reading.\n② Tap \"Start journey\".\n③ When you are back, open this journey from Home, take the end photo and send the claim."), Ui.BRAND_LIGHT, Ui.BRAND_DARK), lp().margins(this, b = 10))
        else if (fixClaim != null) body.addView(note(tr("Reject का कारण: ", "Reason for rejection: ") + fixClaim!!.remarks.ifBlank { "—" } +
            tr("\nजो गलत था उसे ठीक करें और फिर से भेजें। पुरानी photos लगी रहेंगी।", "\nCorrect what was wrong and send it again. The photos already sent stay attached."), Ui.DANGER_LIGHT, Ui.DANGER), lp().margins(this, b = 10))
        else if (journeyMode) body.addView(note(tr("अब आखिर के odometer की photo लें, reading भरें और Claim भेजें।", "Now take the end odometer photo, enter the reading and send the claim."), Ui.BRAND_LIGHT, Ui.BRAND_DARK), lp().margins(this, b = 10))
        val topViews = mutableListOf<View>()
        var petrolBox: View? = null
        var endTile: View? = null
        var odoHint: View? = null

        body.addView(text(req(tr("ज़रूरी", "Required")), 12.5f, Ui.MUTED) { gravity = android.view.Gravity.END }, lp().margins(this, b = 6))
        body.addView(section(tr("खर्च", "Expense")) {
            addView(label(req(tr("तारीख", "Date"))).also { topViews.add(it) })
            dateBtn = selector(Fmt.date(date), R.drawable.ic_history) {
                pickDate(date) { d ->
                    if ((Fmt.parseIso(d) ?: 0L) > System.currentTimeMillis()) { toast(tr("आगे की तारीख नहीं चलेगी", "A future date is not allowed")); return@pickDate }
                    date = d; dateBtn.text = Fmt.date(d)
                }
            }
            addView(dateBtn, lp())
            addView(label(req(tr("किस चीज़ का खर्च (Category)", "Category"))).also { topViews.add(it) })
            catBtn = selector(tr("Category चुनें", "Choose category"), R.drawable.ic_grid) {
                AlertDialog.Builder(this@ClaimFormActivity).setTitle("Category")
                    .setItems(categories.toTypedArray()) { _, i ->
                        category = categories[i]; catBtn.text = category; catBtn.setTextColor(Ui.TEXT)
                        val fuel = Settings.isFuel(category)
                        kmBox.visibility = if (needsKm(category) && !fuel) View.VISIBLE else View.GONE
                        fuelBox.visibility = if (fuel) View.VISIBLE else View.GONE
                        amountBox.visibility = if (fuel) View.GONE else View.VISIBLE
                        if (fuel) updateFuel()
                        updateBill()
                        checkAdvance(bal)
                    }.show()
            }
            addView(catBtn, lp())
            amountBox = vertical {
                amount = field(this, "Amount (₹)", "", tr("जैसे 350", "e.g. 350"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, R.drawable.ic_rupee, 9, required = true) { checkAdvance(bal) }
                amount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            }
            addView(amountBox, lp())
            desc = field(this, if (journeyMode) tr("यात्रा किस काम के लिए", "Purpose of travel") else tr("किस काम के लिए", "Purpose"), "", tr("जैसे: Danapur site survey visit", "e.g. Danapur site survey visit"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit, 300, required = true) {}
            // the person's recent purposes, one tap instead of typing
            suggest(this, recent(journeyMode) { it.description }.map { it.first }) { desc.setText(it); desc.setSelection(desc.text.length) }
            kmBox = vertical {
                visibility = View.GONE
                km = field(this, tr("कितने KM चले", "KM travelled"), "", tr("जैसे 42", "e.g. 42"), InputType.TYPE_CLASS_NUMBER, R.drawable.ic_location, 5) {}
            }
            addView(kmBox, lp())
        })

        fuelBox = section(tr("Fuel / Petrol", "Fuel / petrol")) {
            // own / company bike: one segmented row
            bikeChips = horizontal { }
            addView(bikeChips, lp())
            fuelHint = text("", 12f, Ui.MUTED)
            addView(fuelHint, lp().margins(context, t = 6))
            companyBox = vertical {
                addView(horizontal {
                    addView(vertical {
                        bikeNo = field(this, tr("Bike number", "Bike number"), "", "BR01 AB 4521", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, 0, 20, required = true) {}
                    }, lp(0, WRAP, 1.25f).margins(context, r = 8))
                    addView(vertical {
                        petrolBox = this
                        petrol = field(this, tr("Petrol (₹)", "Petrol (₹)"), "", "300", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 0, 9, required = true) { updateFuel() }
                        petrol.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                        petrol.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }, lp(0, WRAP, 1f))
                })
                if (!starting) addView(text(tr("नीचे Bill में petrol bill की photo ज़रूरी।", "Petrol bill photo required in Bill below."), 12f, Ui.SUN_TEXT, bold = true), lp().margins(context, t = 4))
            }.apply { visibility = View.GONE }
            addView(companyBox, lp())
            // start and end odometer side by side
            addView(horizontal {
                addView(odoTile(odoStart, tr("शुरू", "Start")), lp(0, WRAP, 1f).margins(context, r = 8))
                endTile = odoTile(odoEnd, tr("आखिर", "End"))
                addView(endTile!!, lp(0, WRAP, 1f))
            }, lp().margins(context, t = 12))
            odoHint = text(tr("📷 सिर्फ GPS Camera · आखिर की photo शुरू के बाद की", "📷 GPS camera only · end photo after the start photo"), 11.5f, Ui.MUTED) { gravity = Gravity.CENTER }
            addView(odoHint!!, lp().margins(context, t = 6))
            dateNote = text("", 12.5f, Ui.SUN_TEXT, bold = true) { visibility = View.GONE }
            addView(dateNote, lp().margins(context, t = 8))
            fuelSum = text("", 16.5f, Ui.BRAND_DARK, bold = true) {
                background = rounded(Ui.BRAND_LIGHT, dpf(14f), Ui.BRAND, dp(1))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            addView(fuelSum, lp().margins(context, t = 10))
            draftBtn = pillButton(tr("Draft Save करें (बाद में पूरा करें)", "Save draft — finish later"), Ui.BRAND, iconRes = R.drawable.ic_history, heightDp = 54) { saveDraft() }
            addView(draftBtn, lp().margins(context, t = 12))
        }.apply { visibility = View.GONE }
        body.addView(fuelBox)
        showBike()

        val whoPaid = section(tr("पैसे किसने दिए", "Who paid") + " *") {
            addView(chips(listOf(tr("Advance से", "From advance"), tr("अपने पैसे से", "Own money")), if (paidBy == "Self") tr("अपने पैसे से", "Own money") else tr("Advance से", "From advance")) { pick ->
                paidBy = when (pick) { tr("Advance से", "From advance") -> "Advance"; else -> "Self" }
                checkAdvance(bal)
            }, lp())
            advanceNote = text("", 13f, Ui.SUN_TEXT) { visibility = View.GONE }
            addView(advanceNote, lp().margins(context, t = 8))
            addView(text(tr("Advance से = आपके Advance में से कटेगा। अपने पैसे से = Office आपको वापस देगा।", "From advance = deducted from your advance. Own money = the office pays you back."), 12.5f, Ui.MUTED), lp().margins(context, t = 8))
            addView(label(req(tr("Payment कैसे किया", "How was it paid"))))
            addView(chips(modes, paymentMode) { paymentMode = it }, lp())
            checkAdvance(bal)
        }
        body.addView(whoPaid)

        billSection = section("Bill", tr("Bill की साफ photo लगाएँ।", "Add a clear photo of the bill.")) {
            notice = marquee(noticeText())
            addView(notice, lp().margins(context, b = 10))
            photoBox = vertical { }
            addView(photoBox, lp())
            addView(horizontal {
                addView(pillButton(tr("➕ GPS Camera", "➕ GPS Camera"), Ui.BRAND, iconRes = R.drawable.ic_camera, heightDp = 46) { openCamera() }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton(tr("➕ Gallery", "➕ Gallery"), Ui.BRAND_LIGHT, Ui.TEXT, iconRes = R.drawable.ic_grid, heightDp = 46) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, lp(0, WRAP, 1f))
            }, lp().margins(context, t = 8))
            billNo = field(this, tr("Bill / Voucher No. (अगर हो)", "Bill / voucher no. (if any)"), "", "", InputType.TYPE_CLASS_TEXT, R.drawable.ic_pdf, 40) {}
        }
        body.addView(billSection)

        val siteSection = section(tr("Site (अगर site का खर्च है)", "Site (for site expenses)")) {
            site = field(this, tr("Customer / Site का नाम", "Customer / site name"), "", tr("जैसे: Ramesh Kumar", "e.g. Ramesh Kumar"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person, 100) {}
            district = field(this, tr("District / जगह", "District / location"), "", tr("जैसे: Danapur, Patna", "e.g. Danapur, Patna"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_location, 80) {}
            val sites = recent(null) { it.site }
            if (sites.isNotEmpty()) {
                addView(text(tr("हाल की sites", "Recent sites"), 12f, Ui.MUTED, bold = true), lp().margins(context, t = 10))
                suggest(this, sites.map { it.first }) { name ->
                    site.setText(name)
                    sites.firstOrNull { it.first == name }?.second?.district?.takeIf { it.isNotBlank() }?.let { district.setText(it) }
                }
            }
        }
        // a journey rarely needs the site: it opens only when asked for
        val siteLink = text(tr("＋ Site की जानकारी जोड़ें (ज़रूरी नहीं)", "＋ Add site details (optional)"), 14.5f, Ui.BRAND, bold = true) {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE; siteSection.visibility = View.VISIBLE }
        }
        body.addView(siteLink, lp())
        body.addView(siteSection)
        showPhoto()
        updateBill()

        // bike journey: Fuel / Petrol straight away, and a draft to continue
        if (jx != null) {
            val fuelCat = categories.firstOrNull { Settings.isFuel(it) } ?: "Fuel / Petrol"
            category = fuelCat; catBtn.text = fuelCat; catBtn.setTextColor(Ui.TEXT)
            kmBox.visibility = View.GONE; fuelBox.visibility = View.VISIBLE; amountBox.visibility = View.GONE
            // a journey is always Fuel / Petrol on its own date: no need to show those rows
            topViews.forEach { it.visibility = View.GONE }
            dateBtn.visibility = View.GONE; catBtn.visibility = View.GONE
        }
        if (starting) {
            // step 1 asks only for the start odometer (and the bike); the rest comes when the rider is back
            endTile?.visibility = View.GONE; odoHint?.visibility = View.GONE; petrolBox?.visibility = View.GONE
            fuelSum.visibility = View.GONE; draftBtn.visibility = View.GONE
            whoPaid.visibility = View.GONE; siteSection.visibility = View.GONE
        }
        journey?.let { j ->
            date = j.optString("date").ifBlank { date }; dateBtn.text = Fmt.date(date)
            desc.setText(j.optString("description")); site.setText(j.optString("site")); district.setText(j.optString("district"))
            odoStart.photo = j.optString("startPhoto")
            odoStart.time = j.optLong("startTime", 0L).takeIf { it > 0 }
            odoStart.stamp = j.optString("startStamp").ifBlank { PhotoStamp.read(odoStart.photo)?.text ?: "" }
            odoStart.reading.setText(j.optString("startReading"))
            showOdo(odoStart)
            bike = j.optString("bike", "Own").ifBlank { "Own" }
            bikeNo.setText(j.optString("bikeNo"))
            showBike()
        }
        if (journeyMode && !starting && site.text.isBlank() && district.text.isBlank()) { siteSection.visibility = View.GONE; siteLink.visibility = View.VISIBLE }
        fixClaim?.let { f ->
            // the category and date of a claim stay as they were; everything else can be corrected
            category = f.category; catBtn.text = category; date = f.date.ifBlank { date }
            topViews.forEach { it.visibility = View.GONE }
            dateBtn.visibility = View.GONE; catBtn.visibility = View.GONE
            val fuel = Settings.isFuel(category)
            kmBox.visibility = if (needsKm(category) && !fuel) View.VISIBLE else View.GONE
            fuelBox.visibility = if (fuel) View.VISIBLE else View.GONE
            amountBox.visibility = if (fuel) View.GONE else View.VISIBLE
            amount.setText(plain(f.amount))
            desc.setText(f.description.substringBefore(" (Advance से").trim())
            site.setText(f.site); district.setText(f.district); billNo.setText(f.billNo)
            if (!fuel && f.km > 0) km.setText(plain(f.km))
            if (fuel) {
                bike = if (f.bike == "Company") "Company" else "Own"
                bikeNo.setText(f.bikeNo)
                if (bike == "Company") petrol.setText(plain(f.amount))
                keepStart = f.hasOdoStart; keepEnd = f.hasOdoEnd
                if (f.odoStart > 0) odoStart.reading.setText(plain(f.odoStart))
                if (f.odoEnd > 0) odoEnd.reading.setText(plain(f.odoEnd))
                showOdo(odoStart); showOdo(odoEnd)
                showBike()
            }
            remoteBills = if (f.hasPhoto) maxOf(f.billCount, 1) else 0
            showPhoto(); updateBill(); checkAdvance(bal)
        }
        updateFuel()
    }

    private fun plain(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    /**
     * The person's latest distinct values of one field (purpose, site), newest first, each with the claim it came from.
     * fuelOnly: true = bike journeys only, false = other claims only, null = all.
     */
    private fun recent(fuelOnly: Boolean?, pick: (Claim) -> String): List<Pair<String, Claim>> = try {
        (Outbox.asClaims(this) + Claim.list(Store.load(this, "mine")?.optJSONArray("claims")))
            .filter { fuelOnly == null || it.isFuel == fuelOnly }
            .sortedByDescending { it.date + it.submittedOn }
            .map { pick(it).substringBefore(" (Advance से").trim() to it }
            .filter { it.first.length >= 3 }
            .distinctBy { it.first.lowercase() }
            .take(5)
    } catch (_: Exception) { emptyList() }

    /** A row of small chips under a field: tap one to fill the field. */
    private fun suggest(parent: LinearLayout, items: List<String>, onPick: (String) -> Unit) {
        if (items.isEmpty()) return
        val row = horizontal { }
        items.forEach { s ->
            row.addView(pill(if (s.length > 30) s.take(29) + "…" else s, Ui.BRAND_LIGHT, Ui.BRAND_DARK).apply {
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isClickable = true
                setOnClickListener { onPick(s) }
            }, lp(WRAP, WRAP).margins(this, r = 6))
        }
        parent.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, lp().margins(this, t = 8))
    }

    /** The bill notice: the ₹500 rule always, plus the fuel/bike lines only for Fuel / Petrol. */
    private fun noticeText(): String {
        val base = tr("⚠ ₹500 से ऊपर के हर खर्च के लिए bill की photo ज़रूरी है — बिना bill के claim reject हो सकता है।",
            "⚠ A bill photo is required for every expense above ₹500. Claims without a bill may be rejected.")
        if (!Settings.isFuel(category)) return base
        return base + tr("   ⚠ Fuel / Petrol (अपनी bike) में odometer की GPS photos ही काफी हैं।   ⚠ Company bike: petrol bill ज़रूरी है।",
            "   ⚠ Fuel / petrol (own bike): the odometer GPS photos are enough.   ⚠ Company bike: the petrol bill is required.")
    }

    private fun updateNotice() {
        val t = noticeText()
        notice?.apply { text = "$t          $t"; isSelected = true }
    }

    /** Own-bike fuel is paid per km with no bill, so the Bill section is hidden for it. */
    private fun updateBill() {
        if (!::billSection.isInitialized) return
        updateNotice()
        val hide = starting || (Settings.isFuel(category) && bike == "Own")
        billSection.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun showBike() {
        bikeChips.removeAllViews()
        bikeChips.background = rounded(Ui.BRAND_LIGHT, dpf(14f))
        bikeChips.setPadding(dp(4), dp(4), dp(4), dp(4))
        for ((key, label) in listOf("Own" to tr("🏍 अपनी bike", "🏍 Own bike"), "Company" to tr("🏢 Company bike", "🏢 Company bike"))) {
            val on = bike == key
            bikeChips.addView(text(label, 14.5f, if (on) android.graphics.Color.WHITE else Ui.TEXT, bold = true) {
                gravity = Gravity.CENTER
                background = rounded(if (on) Ui.BRAND else android.graphics.Color.TRANSPARENT, dpf(11f))
                isClickable = true
                contentDescription = label + if (on) " ✓" else ""
                setOnClickListener { if (bike != key) { bike = key; showBike(); updateFuel() } }
            }, lp(0, dp(44), 1f))
        }
        companyBox.visibility = if (bike == "Company") View.VISIBLE else View.GONE
        fuelHint.text = if (bike == "Company") tr("Bill जितना पैसा मिलेगा · km और ₹/km record के लिए", "You get the bill amount · km and ₹/km shown for the record")
            else tr("₹${Fmt.km(fuelRate)} प्रति km · bill की ज़रूरत नहीं", "₹${Fmt.km(fuelRate)} per km · no bill needed")
        updateBill()
    }

    /** Saves the journey so far (start photo and reading) on the phone, as a draft. */
    private fun saveDraft() {
        if (odoStart.photo.isBlank()) { toast(tr("पहले शुरू के odometer की photo लें", "Take the start odometer photo first")); return }
        if (odoValue(odoStart) <= 0) { odoStart.reading.error = tr("Reading डालें", "Enter the reading"); return }
        val j = (journey ?: JSONObject()).put("id", claimId).put("date", date)
            .put("startPhoto", odoStart.photo).put("startTime", odoStart.time ?: 0L).put("startStamp", odoStart.stamp).put("startReading", odoStart.reading.text.toString().trim())
            .put("description", desc.text.toString().trim()).put("site", site.text.toString().trim()).put("district", district.text.toString().trim())
            .put("bike", bike).put("bikeNo", bikeNo.text.toString().trim())
            .put("savedAt", System.currentTimeMillis())
        Journeys.save(this, j)
        toast(tr("Journey draft save हो गया। लौटने पर Home से खोलें।", "Journey saved as a draft. Open it from the home screen when you are back."))
        finish()
    }

    private fun sameDay(a: Long?, b: Long?): Boolean? {
        if (a == null || b == null) return null
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return f.format(Date(a)) == f.format(Date(b))
    }

    private fun needsKm(cat: String) = cat.contains("Fuel", true) || cat.contains("Travel", true) || cat.contains("Vehicle", true)

    private fun label(t: CharSequence): View = text(t, 13f, Ui.MUTED, bold = true).also { it.layoutParams = lp().margins(this, t = 12, b = 6) }

    private fun selector(value: String, iconRes: Int, onClick: () -> Unit) = text(value, 16f, Ui.MUTED) {
        background = rounded(android.graphics.Color.WHITE, dpf(12f), 0xFFC8BDD8.toInt(), dp(1))
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, iconRes, 0)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Ui.BRAND)
        isClickable = true
        foreground = context.ripple()
        setOnClickListener { onClick() }
    }

    private fun checkAdvance(bal: Balance) {
        if (paidBy != "Advance") { advanceNote.visibility = View.GONE; return }
        val a = amount.text.toString().toDoubleOrNull() ?: 0.0
        val over = a > bal.advanceBalance && bal.advanceBalance >= 0
        advanceNote.visibility = View.VISIBLE
        advanceNote.text = tr("आपके पास Advance: ${Fmt.rupees(bal.advanceBalance)}", "Advance with you: ${Fmt.rupees(bal.advanceBalance)}") +
            if (over) tr("\n${Fmt.rupees(bal.advanceBalance)} Advance से कटेगा, बाकी ${Fmt.rupees(a - bal.advanceBalance)} Accounts आपको pay करेगा।",
                "\n${Fmt.rupees(bal.advanceBalance)} comes from your advance; the rest ${Fmt.rupees(a - bal.advanceBalance)} will be paid to you by Accounts.") else ""
        advanceNote.setTextColor(if (over) Ui.SUN_TEXT else Ui.SUN_TEXT)
    }

    private fun openCamera() {
        camera.launch(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_DIR, Photos.dir(this).absolutePath)
            .putExtra(CameraActivity.EXTRA_TITLE, tr("Bill की photo", "Bill photo"))
            .putExtra(CameraActivity.EXTRA_SUBTITLE, category.ifBlank { "Expense claim" })
            .putExtra(CameraActivity.EXTRA_PREFIX, claimId))
    }

    private fun showPhoto() {
        photoBox.removeAllViews()
        photos.removeAll { !File(it).exists() }
        if (photos.isEmpty()) {
            photoBox.addView(if (remoteBills > 0) note(tr("$remoteBills bill photo पहले से लगी है। नई photo तभी लगाएँ जब पुरानी बदलनी हो।", "$remoteBills bill photo" + (if (remoteBills > 1) "s are" else " is") + " already attached. Add a new photo only to replace " + (if (remoteBills > 1) "them." else "it.")), Ui.GREEN_LIGHT, Ui.TEXT)
                else note(tr("अभी कोई photo नहीं लगी।", "No photo added yet."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
            return
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        photos.toList().forEachIndexed { i, p ->
            if (photos.size > 1) photoBox.addView(text(tr("Bill ${i + 1}", "Bill ${i + 1}"), 12.5f, Ui.MUTED, bold = true), lp().margins(this, t = if (i == 0) 0 else 10, b = 4))
            photoBox.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(p, opts))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Ui.BG, dpf(12f))
            }, lp(MATCH, dp(220)))
            photoBox.addView(text(tr("यह photo हटाएँ", "Remove this photo"), 13f, Ui.DANGER, bold = true) {
                gravity = Gravity.END
                setPadding(0, dp(6), 0, 0)
                setOnClickListener { photos.remove(p); showPhoto() }
            }, lp())
        }
        photoBox.addView(text(tr("➕ और photo जोड़ें — नीचे GPS Camera या Gallery दबाएँ", "➕ Add more — tap GPS Camera or Gallery below"), 12.5f, Ui.BRAND_DARK, bold = true), lp().margins(this, t = 10))
    }

    private fun submit() {
        val a = amount.text.toString().toDoubleOrNull() ?: 0.0
        if (category.isBlank()) { toast(tr("Category चुनें", "Choose category")); return }
        if (Settings.isFuel(category)) { submitFuel(); return }
        if (a <= 0) { amount.error = tr("Amount डालें", "Enter the amount"); return }
        if (a > 500000) { amount.error = tr("Amount सही नहीं लगता", "Amount looks wrong"); return }
        if (desc.text.toString().trim().length < 3) { desc.error = tr("किस काम के लिए था, लिखें", "Write what it was for"); return }
        if (paymentMode.isBlank()) { toast(tr("Payment कैसे किया, चुनें", "Choose how it was paid")); return }
        if (photos.isEmpty() && remoteBills == 0 && a > 500) {
            confirm(tr("Bill photo नहीं है", "No bill photo"), tr("₹500 से ऊपर के claim में bill ज़रूरी है। बिना bill के claim reject हो सकता है। फिर भी भेजें?", "A bill is required for claims above ₹500. Without it the claim may be rejected. Send anyway?"), tr("भेजें", "Send")) { save(a) }
            return
        }
        save(a)
    }

    private fun launchOdoCamera(o: Odo, title: String) {
        odoTarget = o
        camera.launch(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_DIR, Photos.dir(this).absolutePath)
            .putExtra(CameraActivity.EXTRA_TITLE, title)
            .putExtra(CameraActivity.EXTRA_SUBTITLE, tr("Odometer साफ दिखे", "Keep the odometer clear in view"))
            .putExtra(CameraActivity.EXTRA_PREFIX, claimId + "_odo_" + o.key)
            .putExtra(CameraActivity.EXTRA_ODOMETER, true))
    }

    /** One odometer: a photo tile (tap = GPS camera) and the reading under it. */
    private fun odoTile(o: Odo, title: String): View = vertical {
        val camTitle = tr("$title का Odometer", "$title odometer")
        addView(text(req(camTitle), 13f, Ui.MUTED, bold = true), lp().margins(context, b = 6))
        o.box = vertical {
            gravity = Gravity.CENTER
            background = rounded(Ui.BG, dpf(14f), 0xFFC8BDD8.toInt(), dp(1))
            isClickable = true
            foreground = context.ripple()
            clipToOutline = true
            setOnClickListener { launchOdoCamera(o, camTitle) }
        }
        addView(o.box, lp(MATCH, dp(112)))
        o.cap = text("", 11.5f, Ui.MUTED) { gravity = Gravity.CENTER }
        addView(o.cap, lp().margins(context, t = 3))
        o.reading = input(tr("Reading", "Reading"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "", 8, required = true).apply {
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
        }
        o.reading.onChange { updateFuel() }
        addView(o.reading, lp().margins(context, t = 4))
        o.hint = text("", 11.5f, Ui.MUTED) { visibility = View.GONE; gravity = Gravity.CENTER }
        addView(o.hint, lp().margins(context, t = 3))
        o.alts = horizontal { visibility = View.GONE; gravity = Gravity.CENTER }
        addView(o.alts, lp().margins(context, t = 3))
        showOdo(o)
    }

    private fun setOdoPhoto(o: Odo, path: String) {
        odoTarget = null
        // only a GPS-camera photo with a valid stamp; the end photo must be newer than the start photo
        val st = PhotoStamp.read(path)
        if (st == null) {
            alert(tr("यह photo नहीं चलेगी", "This photo cannot be used"), tr("Odometer की photo GPS Camera से, GPS location के साथ होनी चाहिए। फिर से photo लें।", "The odometer photo must be taken with the GPS camera, with GPS location. Please take it again."))
            return
        }
        if (o === odoEnd && odoStart.time != null && st.time <= odoStart.time!!) {
            alert(tr("आखिर की photo पुरानी है", "End photo is older"), tr("आखिर के odometer की photo, शुरू की photo के बाद की होनी चाहिए। Journey खत्म होने पर नई photo लें।", "The end odometer photo must be taken after the start photo. Take a new photo at the end of the journey."))
            return
        }
        if (o === odoStart && odoEnd.time != null && odoEnd.time!! <= st.time) {
            odoEnd.photo = ""; odoEnd.time = null; odoEnd.stamp = ""; odoEnd.reading.setText(""); showOdo(odoEnd)
            toast(tr("आखिर की photo हटा दी गई — वह शुरू की photo से पहले की थी", "End photo removed: it was taken before the start photo"))
        }
        val takenAt = st.time
        o.photo = path
        o.time = takenAt
        o.stamp = st.text
        // the claim date follows the start-odometer photo
        if (o === odoStart && takenAt != null && takenAt <= System.currentTimeMillis()) { date = Fmt.isoDay(takenAt); dateBtn.text = Fmt.date(date) }
        updateFuel()
        showOdo(o)
        o.hint.visibility = View.VISIBLE
        o.hint.setTextColor(Ui.MUTED)
        o.hint.text = tr("Photo से reading पढ़ी जा रही है…", "Reading the number from the photo…")
        o.alts.removeAllViews(); o.alts.visibility = View.GONE
        // end reading: should be above the start; start reading: likely close to the last end reading
        val after = if (o === odoEnd) odoValue(odoStart).takeIf { it > 0 } else null
        val near = if (o === odoStart) lastOdometer() else null
        Thread {
            val res = OdometerReader.read(path, after, near)
            val v = res?.value
            runOnUiThread {
                if (isFinishing || o.photo != path) return@runOnUiThread
                val typed = o.reading.text.toString().trim()
                if (res != null && v != null && (typed.isBlank() || typed == o.auto?.toString())) {
                    o.auto = v
                    o.reading.setText(v.toString())
                    o.hint.setTextColor(if (res.sure) Ui.SUN_TEXT else Ui.DANGER)
                    o.hint.text = if (res.sure) tr("Photo से पढ़ा: $v — photo से मिलाकर देखें।", "Read from the photo: $v. Check it against the photo.")
                        else tr("Photo से पढ़ा: $v — पक्का नहीं, ध्यान से मिलाएँ।", "Read from the photo: $v, not sure. Check it carefully.")
                    if (res.alternatives.isNotEmpty()) {
                        o.alts.visibility = View.VISIBLE
                        o.alts.addView(text(tr("या: ", "Or: "), 13f, Ui.MUTED, bold = true))
                        res.alternatives.forEach { alt ->
                            o.alts.addView(pill(alt.toString(), Ui.BRAND_LIGHT, Ui.BRAND_DARK).apply {
                                setPadding(dp(12), dp(8), dp(12), dp(8))
                                isClickable = true
                                setOnClickListener { o.auto = alt; o.reading.setText(alt.toString()); o.alts.visibility = View.GONE; updateFuel() }
                            }, lp(WRAP, WRAP).margins(this, r = 6))
                        }
                    }
                } else if (v == null) {
                    o.hint.setTextColor(Ui.DANGER)
                    o.hint.text = tr("Photo से reading नहीं पढ़ी जा सकी। Reading खुद लिखें।", "Could not read the number from the photo. Type the reading.")
                } else o.hint.visibility = View.GONE
            }
        }.start()
    }

    /** The last end-odometer reading of this person's fuel claims (to help read the next start). */
    private fun lastOdometer(): Double? {
        val sameBike = { c: Claim -> if (bike == "Company") c.bike == "Company" && c.bikeNo.equals(bikeNo.text.toString().trim(), true) else c.bike != "Company" }
        val claims = Outbox.asClaims(this) + Claim.list(Store.load(this, "mine")?.optJSONArray("claims"))
        return claims.filter { it.isFuel && it.odoEnd > 0 && sameBike(it) }.maxByOrNull { it.date + it.submittedOn }?.odoEnd
    }

    private fun showOdo(o: Odo) {
        o.box.removeAllViews()
        if ((o.photo.isBlank() || !File(o.photo).exists()) && (if (o === odoStart) keepStart else keepEnd)) {
            o.box.addView(icon(R.drawable.ic_check, Ui.GREEN, 30), lp(dp(30), dp(30)))
            o.box.addView(text(tr("Photo पहले से लगी है", "Photo already sent"), 12.5f, Ui.GREEN, bold = true), lp(WRAP, WRAP).margins(this, t = 6))
            o.cap.text = tr("बदलनी हो तो tap करें", "tap to retake")
            return
        }
        if (o.photo.isBlank() || !File(o.photo).exists()) {
            o.box.addView(icon(R.drawable.ic_camera, Ui.BRAND, 30), lp(dp(30), dp(30)))
            o.box.addView(text(tr("Tap: GPS photo", "Tap: GPS photo"), 12.5f, Ui.BRAND, bold = true), lp(WRAP, WRAP).margins(this, t = 6))
            o.cap.text = ""
            return
        }
        o.box.addView(ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(o.photo, BitmapFactory.Options().apply { inSampleSize = 4 }))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, lp(MATCH, MATCH))
        o.cap.text = o.time?.let { "✓ " + SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH).format(Date(it)) + tr(" · फिर से: tap", " · tap to retake") } ?: ""
    }

    private fun odoValue(o: Odo) = o.reading.text.toString().trim().toDoubleOrNull() ?: 0.0

    private fun petrolAmount() = if (::petrol.isInitialized) petrol.text.toString().trim().toDoubleOrNull() ?: 0.0 else 0.0

    /** km and ₹ (own bike: km × rate; company bike: the petrol bill), or null while incomplete or wrong. */
    private fun fuelCalc(): Pair<Double, Double>? {
        val a = odoValue(odoStart); val b = odoValue(odoEnd)
        if (a <= 0 || b <= 0 || b <= a) return null
        val km = Math.round((b - a) * 10) / 10.0
        if (bike == "Company") { val p = petrolAmount(); return if (p > 0) km to p else null }
        return km to Math.round(km * fuelRate * 100) / 100.0
    }

    private fun perKm(km: Double, amount: Double) = if (km > 0) "₹" + String.format(Locale.US, "%.2f", amount / km) + "/km" else "" 

    private fun updateFuel() {
        if (!::fuelSum.isInitialized) return
        if (::draftBtn.isInitialized) draftBtn.visibility = if (!starting && fixClaim == null && odoEnd.photo.isBlank()) View.VISIBLE else View.GONE
        val same = sameDay(odoStart.time, odoEnd.time)
        val bothPhotos = odoStart.photo.isNotBlank() && odoEnd.photo.isNotBlank()
        dateNote.visibility = if (bothPhotos && same != true) View.VISIBLE else View.GONE
        dateNote.text = if (same == false) tr("शुरू और आखिर की photo अलग-अलग तारीख की हैं। यह Claim सिर्फ Atul या Rahul approve करेंगे।", "The start and end photos are on different dates. Only Atul or Rahul can approve this claim.")
            else tr("एक photo में तारीख नहीं मिली। यह Claim सिर्फ Atul या Rahul approve करेंगे।", "One photo has no date. Only Atul or Rahul can approve this claim.")
        val a = odoValue(odoStart); val b = odoValue(odoEnd)
        val calc = fuelCalc()
        fuelSum.setTextColor(if (calc == null && a > 0 && b > 0) Ui.DANGER else Ui.TEXT)
        fuelSum.text = when {
            calc != null && bike == "Company" -> "${Fmt.km(calc.first)} km · ${Fmt.rupees(calc.second)} · ${perKm(calc.first, calc.second)}"
            calc != null -> "${Fmt.km(calc.first)} km × ₹${Fmt.km(fuelRate)} = ${Fmt.rupees(calc.second)}"
            bike == "Company" && a > 0 && b > a -> tr("Petrol का amount डालें (bill जितना)", "Enter the petrol amount (as on the bill)")
            a > 0 && b > 0 -> tr("आखिर की reading शुरू से ज़्यादा होनी चाहिए।", "End reading must be more than the start reading.")
            else -> tr("दोनों readings डालें: KM × ₹${Fmt.km(fuelRate)}", "Enter both readings: km × ₹${Fmt.km(fuelRate)}")
        }
    }

    private fun submitFuel() {
        // correcting a rejected claim: a photo already with the office counts, unless it is retaken
        val ks = keepStart && odoStart.photo.isBlank()
        val ke = keepEnd && odoEnd.photo.isBlank()
        if (odoStart.photo.isBlank() && !ks) { toast(tr("शुरू के odometer की photo लगाएँ", "Add the start odometer photo")); return }
        if (odoEnd.photo.isBlank() && !ke) { toast(tr("आखिर के odometer की photo लगाएँ", "Add the end odometer photo")); return }
        if ((!ks && odoStart.stamp.isBlank()) || (!ke && odoEnd.stamp.isBlank())) { toast(tr("Odometer photos GPS Camera से फिर लें", "Take the odometer photos again with the GPS camera")); return }
        if (!ks && !ke && (odoEnd.time ?: 0L) <= (odoStart.time ?: 0L)) { toast(tr("आखिर की photo शुरू की photo के बाद की होनी चाहिए", "The end photo must be newer than the start photo")); return }
        if (odoValue(odoStart) <= 0) { odoStart.reading.error = tr("Reading डालें", "Enter the reading"); return }
        if (odoValue(odoEnd) <= 0) { odoEnd.reading.error = tr("Reading डालें", "Enter the reading"); return }
        if (bike == "Company") {
            if (bikeNo.text.toString().trim().length < 4) { bikeNo.error = tr("Bike number डालें", "Enter the bike number"); return }
            if (petrolAmount() <= 0) { petrol.error = tr("Petrol का amount डालें", "Enter the petrol amount"); return }
            if (photos.isEmpty() && remoteBills == 0) { toast(tr("Company bike: petrol bill की photo लगाएँ", "Company bike: add the petrol bill photo")); return }
        }
        val calc = fuelCalc() ?: run { odoEnd.reading.error = tr("शुरू से ज़्यादा होनी चाहिए", "Must be more than the start"); return }
        if (calc.first > 1500) { odoEnd.reading.error = tr("${Fmt.km(calc.first)} km सही नहीं लगता", "${Fmt.km(calc.first)} km looks wrong"); return }
        if (desc.text.toString().trim().length < 3) { desc.error = tr("किस काम के लिए था, लिखें", "Write what it was for"); return }
        if (paymentMode.isBlank()) { toast(tr("Payment कैसे किया, चुनें", "Choose how it was paid")); return }
        confirm(tr("Fuel claim भेजें?", "Send fuel claim?"),
            "${Fmt.km(odoValue(odoStart))} → ${Fmt.km(odoValue(odoEnd))}\n" + (if (bike == "Company")
                "Company bike ${bikeNo.text.toString().trim().uppercase()}\n${Fmt.km(calc.first)} km · ${Fmt.rupees(calc.second)} · ${perKm(calc.first, calc.second)}"
                else "${Fmt.km(calc.first)} km × ₹${Fmt.km(fuelRate)} = ${Fmt.rupees(calc.second)}"),
            tr("भेजें", "Send")) { save(calc.second) }
    }

    private fun save(a: Double) {
        val fuel = Settings.isFuel(category)
        val claim = JSONObject()
            .put("id", claimId).put("date", date).put("category", category).put("amount", a)
            .put("description", desc.text.toString().trim()).put("paidBy", paidBy).put("paymentMode", paymentMode)
            .put("billNo", billNo.text.toString().trim()).put("site", site.text.toString().trim())
            .put("district", district.text.toString().trim())
            .put("km", if (fuel) (fuelCalc()?.first ?: 0.0) else if (kmBox.visibility == View.VISIBLE) km.text.toString().toDoubleOrNull() ?: 0.0 else 0.0)
            .put("submittedOn", Fmt.today())
        if (fuel) claim.put("odoStart", odoValue(odoStart)).put("odoEnd", odoValue(odoEnd)).put("rate", if (bike == "Company") 0.0 else fuelRate)
            .put("bike", bike).put("bikeNo", if (bike == "Company") bikeNo.text.toString().trim().uppercase() else "")
            .put("odoStartTime", odoStart.time ?: 0L).put("odoEndTime", odoEnd.time ?: 0L)
            .put("odoStartStamp", odoStart.stamp).put("odoEndStamp", odoEnd.stamp)
        if (fixClaim != null) claim.put("fix", true)
        // saved on the phone at once; it goes to the office in the background (no waiting for the upload)
        Outbox.add(this, claim, photos.toList(), if (fuel) odoStart.photo else "", if (fuel) odoEnd.photo else "")
        Journeys.remove(this, claimId)
        Outbox.flush(this)
        toast(tr("Claim save हो गया — background में भेजा जा रहा है।", "Claim saved. It is being sent in the background."))
        finish()
    }
}
