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
    }

    private var date = Fmt.today()
    private var category = ""
    private var paidBy = "Self"
    private var paymentMode = ""
    private var photo = ""

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
    private lateinit var draftBtn: View
    private lateinit var dateNote: android.widget.TextView

    private fun newClaimId(): String {
        val u = Session.user(this)
        return "CLM-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date()) + "-" +
            (u?.id ?: "X").uppercase().replace(Regex("[^A-Z0-9]"), "")
    }

    private val camera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = r.data?.getStringArrayListExtra(CameraActivity.EXTRA_FILES).orEmpty().lastOrNull()
        val odo = odoTarget
        if (f != null) { if (odo != null) setOdoPhoto(odo, f) else { photo = f; showPhoto() } }
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val odo = odoTarget
        if (uri != null) {
            val f = Photos.importUri(this, uri, claimId + (odo?.let { "_odo_" + it.key } ?: "_g") + System.currentTimeMillis() % 100000)
            if (f == null) toast(tr("यह photo नहीं खुल सकी", "Could not open this photo"))
            else { photo = f.absolutePath; showPhoto() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val jx = intent.getStringExtra(EXTRA_JOURNEY)
        journeyMode = jx != null
        journey = if (jx != null && jx != "new") Journeys.get(this, jx) else null
        claimId = journey?.optString("id")?.takeIf { it.isNotBlank() } ?: newClaimId()
        val boot = Store.load(this, "boot")
        val categories = Store.strings(boot, "categories", Store.DEFAULT_CATEGORIES)
        val modes = Store.strings(boot, "paymentModes", Store.DEFAULT_MODES)
        val bal = Balance.from(Store.load(this, "mine")?.optJSONObject("balance") ?: boot?.optJSONObject("balance"))
        fuelRate = Settings.fuelRate(this)

        body = screen(tr("नया Claim", "New claim"), tr("एक bill = एक claim", "One bill = one claim"), bottom = pillButton(tr("Claim भेजें", "Send claim"), Ui.SUN, Ui.TEXT, heightDp = 54) { submit() })

        body.addView(text(req(tr("ज़रूरी", "Required")), 12.5f, Ui.MUTED) { gravity = android.view.Gravity.END }, lp().margins(this, b = 6))
        body.addView(section(tr("खर्च", "Expense")) {
            addView(label(req(tr("तारीख", "Date"))))
            dateBtn = selector(Fmt.date(date), R.drawable.ic_history) {
                pickDate(date) { d ->
                    if ((Fmt.parseIso(d) ?: 0L) > System.currentTimeMillis()) { toast(tr("आगे की तारीख नहीं चलेगी", "A future date is not allowed")); return@pickDate }
                    date = d; dateBtn.text = Fmt.date(d)
                }
            }
            addView(dateBtn, lp())
            addView(label(req(tr("किस चीज़ का खर्च (Category)", "Category"))))
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
            desc = field(this, tr("किस काम के लिए", "Purpose"), "", tr("जैसे: Danapur site survey visit", "e.g. Danapur site survey visit"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit, 300, required = true) {}
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
                        petrol = field(this, tr("Petrol (₹)", "Petrol (₹)"), "", "300", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, 0, 9, required = true) { updateFuel() }
                        petrol.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                        petrol.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }, lp(0, WRAP, 1f))
                })
                addView(text(tr("नीचे Bill में petrol bill की photo ज़रूरी।", "Petrol bill photo required in Bill below."), 12f, Ui.SUN_TEXT, bold = true), lp().margins(context, t = 4))
            }.apply { visibility = View.GONE }
            addView(companyBox, lp())
            // start and end odometer side by side
            addView(horizontal {
                addView(odoTile(odoStart, tr("शुरू", "Start")), lp(0, WRAP, 1f).margins(context, r = 8))
                addView(odoTile(odoEnd, tr("आखिर", "End")), lp(0, WRAP, 1f))
            }, lp().margins(context, t = 12))
            addView(text(tr("📷 सिर्फ GPS Camera · आखिर की photo शुरू के बाद की", "📷 GPS camera only · end photo after the start photo"), 11.5f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(context, t = 6))
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

        body.addView(section(tr("पैसे किसने दिए", "Who paid") + " *") {
            addView(chips(listOf(tr("अपने पैसे से", "Own money"), tr("Advance से", "From advance"), tr("Company ने दिया", "Paid by company")), tr("अपने पैसे से", "Own money")) { pick ->
                paidBy = when (pick) { tr("Advance से", "From advance") -> "Advance"; tr("Company ने दिया", "Paid by company") -> "Company"; else -> "Self" }
                checkAdvance(bal)
            }, lp())
            advanceNote = text("", 13f, Ui.SUN_TEXT) { visibility = View.GONE }
            addView(advanceNote, lp().margins(context, t = 8))
            addView(text(tr("अपने पैसे से = Office आपको वापस देगा। Advance से = आपके Advance में से कटेगा। Company ने दिया = Company ने सीधे pay किया।", "Own money = the office pays you back. From advance = deducted from your advance. Paid by company = AVIP paid directly."), 12.5f, Ui.MUTED), lp().margins(context, t = 8))
            addView(label(req(tr("Payment कैसे किया", "How was it paid"))))
            addView(chips(modes, "") { paymentMode = it }, lp())
        })

        billSection = section("Bill", tr("Bill की साफ photo लगाएँ।", "Add a clear photo of the bill.")) {
            notice = marquee(noticeText())
            addView(notice, lp().margins(context, b = 10))
            photoBox = vertical { }
            addView(photoBox, lp())
            addView(horizontal {
                addView(pillButton("GPS Camera", Ui.BRAND, iconRes = R.drawable.ic_camera, heightDp = 46) { openCamera() }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton("Gallery", Ui.BRAND_LIGHT, Ui.TEXT, iconRes = R.drawable.ic_grid, heightDp = 46) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, lp(0, WRAP, 1f))
            }, lp().margins(context, t = 8))
            billNo = field(this, tr("Bill / Voucher No. (अगर हो)", "Bill / voucher no. (if any)"), "", "", InputType.TYPE_CLASS_TEXT, R.drawable.ic_pdf, 40) {}
        }
        body.addView(billSection)

        body.addView(section(tr("Site (अगर site का खर्च है)", "Site (for site expenses)")) {
            site = field(this, tr("Customer / Site का नाम", "Customer / site name"), "", tr("जैसे: Ramesh Kumar", "e.g. Ramesh Kumar"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person, 100) {}
            district = field(this, tr("District / जगह", "District / location"), "", tr("जैसे: Danapur, Patna", "e.g. Danapur, Patna"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_location, 80) {}
        })
        showPhoto()
        updateBill()

        // bike journey: Fuel / Petrol straight away, and a draft to continue
        if (jx != null) {
            val fuelCat = categories.firstOrNull { Settings.isFuel(it) } ?: "Fuel / Petrol"
            category = fuelCat; catBtn.text = fuelCat; catBtn.setTextColor(Ui.TEXT)
            kmBox.visibility = View.GONE; fuelBox.visibility = View.VISIBLE; amountBox.visibility = View.GONE
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
        updateFuel()
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
        val hide = Settings.isFuel(category) && bike == "Own"
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
        advanceNote.visibility = View.VISIBLE
        advanceNote.text = tr("आपके पास Advance: ${Fmt.rupees(bal.advanceBalance)}", "Advance with you: ${Fmt.rupees(bal.advanceBalance)}") +
            if (a > bal.advanceBalance) tr("\nयह claim Advance से ज़्यादा है। बाकी के लिए अलग claim \"अपने पैसे से\" डालें।", "\nThis claim is more than your advance. Add a separate \"Own money\" claim for the rest.") else ""
        advanceNote.setTextColor(if (a > bal.advanceBalance) Ui.DANGER else Ui.SUN_TEXT)
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
        if (photo.isBlank() || !File(photo).exists()) {
            photoBox.addView(note(tr("अभी कोई photo नहीं लगी।", "No photo added yet."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
            return
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        photoBox.addView(ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(photo, opts))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Ui.BG, dpf(12f))
        }, lp(MATCH, dp(220)))
        photoBox.addView(text(tr("Photo हटाएँ", "Remove photo"), 13f, Ui.DANGER, bold = true) {
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { photo = ""; showPhoto() }
        }, lp())
    }

    private fun submit() {
        val a = amount.text.toString().toDoubleOrNull() ?: 0.0
        if (category.isBlank()) { toast(tr("Category चुनें", "Choose category")); return }
        if (Settings.isFuel(category)) { submitFuel(); return }
        if (a <= 0) { amount.error = tr("Amount डालें", "Enter the amount"); return }
        if (a > 500000) { amount.error = tr("Amount सही नहीं लगता", "Amount looks wrong"); return }
        if (desc.text.toString().trim().length < 3) { desc.error = tr("किस काम के लिए था, लिखें", "Write what it was for"); return }
        if (paymentMode.isBlank()) { toast(tr("Payment कैसे किया, चुनें", "Choose how it was paid")); return }
        if (photo.isBlank() && a > 500) {
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
        if (::draftBtn.isInitialized) draftBtn.visibility = if (odoEnd.photo.isBlank()) View.VISIBLE else View.GONE
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
        if (odoStart.photo.isBlank()) { toast(tr("शुरू के odometer की photo लगाएँ", "Add the start odometer photo")); return }
        if (odoEnd.photo.isBlank()) { toast(tr("आखिर के odometer की photo लगाएँ", "Add the end odometer photo")); return }
        if (odoStart.stamp.isBlank() || odoEnd.stamp.isBlank()) { toast(tr("Odometer photos GPS Camera से फिर लें", "Take the odometer photos again with the GPS camera")); return }
        if ((odoEnd.time ?: 0L) <= (odoStart.time ?: 0L)) { toast(tr("आखिर की photo शुरू की photo के बाद की होनी चाहिए", "The end photo must be newer than the start photo")); return }
        if (odoValue(odoStart) <= 0) { odoStart.reading.error = tr("Reading डालें", "Enter the reading"); return }
        if (odoValue(odoEnd) <= 0) { odoEnd.reading.error = tr("Reading डालें", "Enter the reading"); return }
        if (bike == "Company") {
            if (bikeNo.text.toString().trim().length < 4) { bikeNo.error = tr("Bike number डालें", "Enter the bike number"); return }
            if (petrolAmount() <= 0) { petrol.error = tr("Petrol का amount डालें", "Enter the petrol amount"); return }
            if (photo.isBlank()) { toast(tr("Company bike: petrol bill की photo लगाएँ", "Company bike: add the petrol bill photo")); return }
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
        // saved on the phone at once; it goes to the office in the background (no waiting for the upload)
        Outbox.add(this, claim, photo, if (fuel) odoStart.photo else "", if (fuel) odoEnd.photo else "")
        Journeys.remove(this, claimId)
        Outbox.flush(this)
        toast(tr("Claim save हो गया — background में भेजा जा रहा है।", "Claim saved. It is being sent in the background."))
        finish()
    }
}
