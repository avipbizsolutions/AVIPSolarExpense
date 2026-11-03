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

    private var date = Fmt.today()
    private var category = ""
    private var paidBy = "Self"
    private var paymentMode = ""
    private var photo = ""

    /** Fuel / Petrol: start and end odometer photo + reading. */
    private inner class Odo(val key: String, val title: String) {
        var photo = ""
        var auto: Long? = null
        lateinit var box: LinearLayout
        lateinit var reading: EditText
        lateinit var hint: android.widget.TextView
    }
    private val odoStart = Odo("start", "")
    private val odoEnd = Odo("end", "")
    private var odoTarget: Odo? = null
    private var fuelRate = Settings.DEFAULT_FUEL_RATE
    private lateinit var fuelBox: View
    private lateinit var fuelSum: android.widget.TextView
    private lateinit var amountBox: View

    private lateinit var body: LinearLayout
    private lateinit var dateBtn: android.widget.TextView
    private lateinit var catBtn: android.widget.TextView
    private lateinit var amount: EditText
    private lateinit var desc: EditText
    private lateinit var site: EditText
    private lateinit var district: EditText
    private lateinit var km: EditText
    private lateinit var billNo: EditText
    private lateinit var kmBox: View
    private lateinit var advanceNote: android.widget.TextView
    private lateinit var photoBox: LinearLayout

    private val claimId by lazy {
        val u = Session.user(this)
        "CLM-" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date()) + "-" +
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
            else if (odo != null) setOdoPhoto(odo, f.absolutePath) else { photo = f.absolutePath; showPhoto() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val boot = Store.load(this, "boot")
        val categories = Store.strings(boot, "categories", Store.DEFAULT_CATEGORIES)
        val modes = Store.strings(boot, "paymentModes", Store.DEFAULT_MODES)
        val bal = Balance.from(Store.load(this, "mine")?.optJSONObject("balance") ?: boot?.optJSONObject("balance"))
        fuelRate = Settings.fuelRate(this)

        body = screen(tr("नया Claim", "New claim"), tr("एक bill = एक claim", "One bill = one claim"), bottom = pillButton(tr("Claim भेजें", "Send claim"), Ui.SUN, Ui.TEXT, heightDp = 54) { submit() })

        body.addView(section(tr("खर्च", "Expense")) {
            addView(label(tr("तारीख", "Date")))
            dateBtn = selector(Fmt.date(date), R.drawable.ic_history) {
                pickDate(date) { d ->
                    if ((Fmt.parseIso(d) ?: 0L) > System.currentTimeMillis()) { toast(tr("आगे की तारीख नहीं चलेगी", "A future date is not allowed")); return@pickDate }
                    date = d; dateBtn.text = Fmt.date(d)
                }
            }
            addView(dateBtn, lp())
            addView(label(tr("किस चीज़ का खर्च (Category)", "Category")))
            catBtn = selector(tr("Category चुनें", "Choose category"), R.drawable.ic_grid) {
                AlertDialog.Builder(this@ClaimFormActivity).setTitle("Category")
                    .setItems(categories.toTypedArray()) { _, i ->
                        category = categories[i]; catBtn.text = category; catBtn.setTextColor(Ui.TEXT)
                        val fuel = Settings.isFuel(category)
                        kmBox.visibility = if (needsKm(category) && !fuel) View.VISIBLE else View.GONE
                        fuelBox.visibility = if (fuel) View.VISIBLE else View.GONE
                        amountBox.visibility = if (fuel) View.GONE else View.VISIBLE
                        if (fuel) updateFuel()
                        checkAdvance(bal)
                    }.show()
            }
            addView(catBtn, lp())
            amountBox = vertical {
                amount = field(this, "Amount (₹)", "", tr("जैसे 350", "e.g. 350"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, R.drawable.ic_rupee, 9) { checkAdvance(bal) }
                amount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            }
            addView(amountBox, lp())
            desc = field(this, tr("किस काम के लिए", "Purpose"), "", tr("जैसे: Danapur site survey visit", "e.g. Danapur site survey visit"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit, 300) {}
            kmBox = vertical {
                visibility = View.GONE
                km = field(this, tr("कितने KM चले", "KM travelled"), "", tr("जैसे 42", "e.g. 42"), InputType.TYPE_CLASS_NUMBER, R.drawable.ic_location, 5) {}
            }
            addView(kmBox, lp())
        })

        fuelBox = section(tr("Odometer (Fuel / Petrol)", "Odometer (fuel / petrol)"),
            tr("जाने से पहले और लौटने पर odometer की GPS photo लें। KM और Amount अपने आप बनेगा: ₹${Fmt.km(fuelRate)} प्रति km।",
               "Take a GPS photo of the odometer before you start and when you are back. KM and amount are worked out automatically at ₹${Fmt.km(fuelRate)} per km.")) {
            odoSlot(this, odoStart, tr("शुरू का Odometer", "Start odometer"))
            odoSlot(this, odoEnd, tr("आखिर का Odometer", "End odometer"))
            fuelSum = text("", 17f, Ui.TEXT, bold = true) {
                background = rounded(Ui.BRAND_LIGHT, dpf(12f))
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            addView(fuelSum, lp().margins(context, t = 14))
        }.apply { visibility = View.GONE }
        body.addView(fuelBox)

        body.addView(section(tr("पैसे किसने दिए", "Who paid")) {
            addView(chips(listOf(tr("अपने पैसे से", "Own money"), tr("Advance से", "From advance"), tr("Company ने दिया", "Paid by company")), tr("अपने पैसे से", "Own money")) { pick ->
                paidBy = when (pick) { tr("Advance से", "From advance") -> "Advance"; tr("Company ने दिया", "Paid by company") -> "Company"; else -> "Self" }
                checkAdvance(bal)
            }, lp())
            advanceNote = text("", 13f, Ui.SUN_TEXT) { visibility = View.GONE }
            addView(advanceNote, lp().margins(context, t = 8))
            addView(text(tr("अपने पैसे से = Office आपको वापस देगा। Advance से = आपके Advance में से कटेगा। Company ने दिया = Company ने सीधे pay किया।", "Own money = the office pays you back. From advance = deducted from your advance. Paid by company = AVIP paid directly."), 12.5f, Ui.MUTED), lp().margins(context, t = 8))
            addView(label(tr("Payment कैसे किया", "How was it paid")))
            addView(chips(modes, "") { paymentMode = it }, lp())
        })

        body.addView(section("Bill", tr("Bill की साफ photo लगाएँ। ₹500 से ऊपर के claim में bill ज़रूरी है। (Fuel claim में odometer photos ही काफी हैं।)", "Add a clear photo of the bill. A bill is required for claims above ₹500. (For fuel claims the odometer photos are enough.)")) {
            photoBox = vertical { }
            addView(photoBox, lp())
            addView(horizontal {
                addView(pillButton("GPS Camera", Ui.BRAND, iconRes = R.drawable.ic_camera, heightDp = 46) { openCamera() }, lp(0, WRAP, 1f).margins(context, r = 8))
                addView(pillButton("Gallery", Ui.BRAND_LIGHT, Ui.TEXT, iconRes = R.drawable.ic_grid, heightDp = 46) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, lp(0, WRAP, 1f))
            }, lp().margins(context, t = 8))
            billNo = field(this, tr("Bill / Voucher No. (अगर हो)", "Bill / voucher no. (if any)"), "", "", InputType.TYPE_CLASS_TEXT, R.drawable.ic_pdf, 40) {}
        })

        body.addView(section(tr("Site (अगर site का खर्च है)", "Site (for site expenses)")) {
            site = field(this, tr("Customer / Site का नाम", "Customer / site name"), "", tr("जैसे: Ramesh Kumar", "e.g. Ramesh Kumar"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_person, 100) {}
            district = field(this, tr("District / जगह", "District / location"), "", tr("जैसे: Danapur, Patna", "e.g. Danapur, Patna"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS, R.drawable.ic_location, 80) {}
        })
        showPhoto()
    }

    private fun needsKm(cat: String) = cat.contains("Fuel", true) || cat.contains("Travel", true) || cat.contains("Vehicle", true)

    private fun label(t: String): View = text(t, 13f, Ui.MUTED, bold = true).also { it.layoutParams = lp().margins(this, t = 12, b = 6) }

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

    private fun odoSlot(parent: LinearLayout, o: Odo, title: String) {
        parent.addView(text(title, 15f, Ui.TEXT, bold = true), lp().margins(this, t = 14, b = 6))
        o.box = vertical { }
        parent.addView(o.box, lp())
        parent.addView(horizontal {
            addView(pillButton("GPS Camera", Ui.BRAND, iconRes = R.drawable.ic_camera, heightDp = 44) {
                odoTarget = o
                camera.launch(Intent(this@ClaimFormActivity, CameraActivity::class.java)
                    .putExtra(CameraActivity.EXTRA_DIR, Photos.dir(this@ClaimFormActivity).absolutePath)
                    .putExtra(CameraActivity.EXTRA_TITLE, title)
                    .putExtra(CameraActivity.EXTRA_SUBTITLE, tr("Odometer साफ दिखे", "Keep the odometer clear in view"))
                    .putExtra(CameraActivity.EXTRA_PREFIX, claimId + "_odo_" + o.key))
            }, lp(0, WRAP, 1f).margins(context, r = 8))
            addView(pillButton("Gallery", Ui.BRAND_LIGHT, Ui.TEXT, iconRes = R.drawable.ic_grid, heightDp = 44) {
                odoTarget = o
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, lp(0, WRAP, 1f))
        }, lp().margins(this, t = 6))
        o.reading = field(parent, tr("Reading (photo से)", "Reading (from the photo)"), "", tr("जैसे 12540", "e.g. 12540"),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, R.drawable.ic_edit, 8) { updateFuel() }
        o.hint = text("", 12.5f, Ui.MUTED) { visibility = View.GONE }
        parent.addView(o.hint, lp().margins(this, t = 4))
        showOdo(o)
    }

    private fun setOdoPhoto(o: Odo, path: String) {
        o.photo = path
        odoTarget = null
        showOdo(o)
        o.hint.visibility = View.VISIBLE
        o.hint.setTextColor(Ui.MUTED)
        o.hint.text = tr("Photo से reading पढ़ी जा रही है…", "Reading the number from the photo…")
        Thread {
            val v = OdometerReader.read(path)
            runOnUiThread {
                if (isFinishing || o.photo != path) return@runOnUiThread
                val typed = o.reading.text.toString().trim()
                if (v != null && (typed.isBlank() || typed == o.auto?.toString())) {
                    o.auto = v
                    o.reading.setText(v.toString())
                    o.hint.setTextColor(Ui.SUN_TEXT)
                    o.hint.text = tr("Photo से पढ़ा: $v — photo से मिलाकर देखें, गलत हो तो सही करें।", "Read from the photo: $v. Check it against the photo and correct it if needed.")
                } else if (v == null) {
                    o.hint.setTextColor(Ui.DANGER)
                    o.hint.text = tr("Photo से reading नहीं पढ़ी जा सकी। Reading खुद लिखें।", "Could not read the number from the photo. Type the reading.")
                } else o.hint.visibility = View.GONE
            }
        }.start()
    }

    private fun showOdo(o: Odo) {
        o.box.removeAllViews()
        if (o.photo.isBlank() || !File(o.photo).exists()) {
            o.box.addView(note(tr("अभी photo नहीं लगी।", "No photo yet."), Ui.SUN_LIGHT, Ui.SUN_TEXT))
            return
        }
        o.box.addView(ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeFile(o.photo, BitmapFactory.Options().apply { inSampleSize = 4 }))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Ui.BG, dpf(12f))
        }, lp(MATCH, dp(170)))
    }

    private fun odoValue(o: Odo) = o.reading.text.toString().trim().toDoubleOrNull() ?: 0.0

    /** km and ₹, or null while the readings are incomplete or wrong. */
    private fun fuelCalc(): Pair<Double, Double>? {
        val a = odoValue(odoStart); val b = odoValue(odoEnd)
        if (a <= 0 || b <= 0 || b <= a) return null
        val km = Math.round((b - a) * 10) / 10.0
        return km to Math.round(km * fuelRate * 100) / 100.0
    }

    private fun updateFuel() {
        if (!::fuelSum.isInitialized) return
        val a = odoValue(odoStart); val b = odoValue(odoEnd)
        val calc = fuelCalc()
        fuelSum.setTextColor(if (calc == null && a > 0 && b > 0) Ui.DANGER else Ui.TEXT)
        fuelSum.text = when {
            calc != null -> "${Fmt.km(calc.first)} km × ₹${Fmt.km(fuelRate)} = ${Fmt.rupees(calc.second)}"
            a > 0 && b > 0 -> tr("आखिर की reading शुरू से ज़्यादा होनी चाहिए।", "End reading must be more than the start reading.")
            else -> tr("दोनों readings डालें: KM × ₹${Fmt.km(fuelRate)}", "Enter both readings: km × ₹${Fmt.km(fuelRate)}")
        }
    }

    private fun submitFuel() {
        if (odoStart.photo.isBlank()) { toast(tr("शुरू के odometer की photo लगाएँ", "Add the start odometer photo")); return }
        if (odoEnd.photo.isBlank()) { toast(tr("आखिर के odometer की photo लगाएँ", "Add the end odometer photo")); return }
        if (odoValue(odoStart) <= 0) { odoStart.reading.error = tr("Reading डालें", "Enter the reading"); return }
        if (odoValue(odoEnd) <= 0) { odoEnd.reading.error = tr("Reading डालें", "Enter the reading"); return }
        val calc = fuelCalc() ?: run { odoEnd.reading.error = tr("शुरू से ज़्यादा होनी चाहिए", "Must be more than the start"); return }
        if (calc.first > 1500) { odoEnd.reading.error = tr("${Fmt.km(calc.first)} km सही नहीं लगता", "${Fmt.km(calc.first)} km looks wrong"); return }
        if (desc.text.toString().trim().length < 3) { desc.error = tr("किस काम के लिए था, लिखें", "Write what it was for"); return }
        if (paymentMode.isBlank()) { toast(tr("Payment कैसे किया, चुनें", "Choose how it was paid")); return }
        confirm(tr("Fuel claim भेजें?", "Send fuel claim?"),
            "${Fmt.km(odoValue(odoStart))} → ${Fmt.km(odoValue(odoEnd))}\n${Fmt.km(calc.first)} km × ₹${Fmt.km(fuelRate)} = ${Fmt.rupees(calc.second)}",
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
        if (fuel) claim.put("odoStart", odoValue(odoStart)).put("odoEnd", odoValue(odoEnd)).put("rate", fuelRate)
        Outbox.add(this, claim, photo, if (fuel) odoStart.photo else "", if (fuel) odoEnd.photo else "")
        busy(tr("Claim भेजा जा रहा है…", "Sending claim…"), { Outbox.send(this, claimId) }, onError = { e ->
            if (e is ApiException && e.network) {
                AlertDialog.Builder(this).setTitle(tr("Phone पर save हो गया", "Saved on the phone"))
                    .setMessage(tr("अभी internet नहीं है। Claim इस phone में save है और internet आने पर अपने आप चला जाएगा।", "No internet right now. The claim is saved on this phone and will be sent automatically when the internet is back."))
                    .setPositiveButton("OK") { _, _ -> finish() }.setCancelable(false).show()
                true
            } else {
                // the office refused it (e.g. wrong category): keep the form open to fix it
                if (!(e is ApiException && e.sessionExpired)) Outbox.remove(this, claimId)
                false
            }
        }) {
            toast(tr("Claim भेज दिया। Approval के बाद आपको दिखेगा।", "Claim sent. You will see it update after approval."))
            finish()
        }
    }
}
