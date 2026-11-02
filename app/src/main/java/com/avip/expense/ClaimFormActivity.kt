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
        if (f != null) { photo = f; showPhoto() }
    }

    private val gallery = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val f = Photos.importUri(this, uri, claimId + "_g" + System.currentTimeMillis() % 100000)
            if (f != null) { photo = f.absolutePath; showPhoto() } else toast(tr("यह photo नहीं खुल सकी", "Could not open this photo"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val boot = Store.load(this, "boot")
        val categories = Store.strings(boot, "categories", Store.DEFAULT_CATEGORIES)
        val modes = Store.strings(boot, "paymentModes", Store.DEFAULT_MODES)
        val bal = Balance.from(Store.load(this, "mine")?.optJSONObject("balance") ?: boot?.optJSONObject("balance"))

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
                        kmBox.visibility = if (needsKm(category)) View.VISIBLE else View.GONE
                    }.show()
            }
            addView(catBtn, lp())
            amount = field(this, "Amount (₹)", "", tr("जैसे 350", "e.g. 350"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, R.drawable.ic_rupee, 9) { checkAdvance(bal) }
            amount.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
            desc = field(this, tr("किस काम के लिए", "Purpose"), "", tr("जैसे: Danapur site survey visit", "e.g. Danapur site survey visit"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, R.drawable.ic_edit, 300) {}
            kmBox = vertical {
                visibility = View.GONE
                km = field(this, tr("कितने KM चले", "KM travelled"), "", tr("जैसे 42", "e.g. 42"), InputType.TYPE_CLASS_NUMBER, R.drawable.ic_location, 5) {}
            }
            addView(kmBox, lp())
        })

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

        body.addView(section("Bill", tr("Bill की साफ photo लगाएँ। ₹500 से ऊपर के claim में bill ज़रूरी है।", "Add a clear photo of the bill. A bill is required for claims above ₹500.")) {
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
        background = rounded(android.graphics.Color.WHITE, dpf(12f), 0xFFB9C5D0.toInt(), dp(1))
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

    private fun save(a: Double) {
        val claim = JSONObject()
            .put("id", claimId).put("date", date).put("category", category).put("amount", a)
            .put("description", desc.text.toString().trim()).put("paidBy", paidBy).put("paymentMode", paymentMode)
            .put("billNo", billNo.text.toString().trim()).put("site", site.text.toString().trim())
            .put("district", district.text.toString().trim())
            .put("km", if (kmBox.visibility == View.VISIBLE) km.text.toString().toDoubleOrNull() ?: 0.0 else 0.0)
            .put("submittedOn", Fmt.today())
        Outbox.add(this, claim, photo)
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
