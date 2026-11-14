package com.avip.expense

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Login with mobile number + 4-digit PIN from the AVIP Team Directory: the same
 * login for AVIP Solar Sales, Installation and Expense. The role (Member,
 * Approver, Accounts, Admin) comes from the directory. With the office
 * (temporary) PIN, the person must first set a PIN of their own.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var form: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        if (Session.mustSetPin(this) && Session.token(this).isNotBlank()) showSetPin() else showLogin()
    }

    private fun frame(title: String, subtitle: String): LinearLayout {
        val head = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(16), dp(28), dp(28))
            addView(horizontal {
                gravity = Gravity.END
                addView(langToggle(), lp(WRAP, WRAP))
            }, lp().margins(context, b = 8))
            addView(badge(R.drawable.ic_sun, Ui.SUN, 72, square = true))
            addView(text(subtitle, 15f, 0xFFE4D9F2.toInt()) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 14))
            addView(text(title, 26f, Color.WHITE, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 2))
        }
        form = vertical(24) {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dpf(28f), dpf(28f), dpf(28f), dpf(28f), 0f, 0f, 0f, 0f)
            }
        }
        val root = vertical {
            setBackgroundColor(Ui.BRAND)
            addView(head)
            addView(form, lp(MATCH, 0, 1f))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
        return form
    }

    private fun showLogin() {
        val f = frame("Solar Expense", "AVIP Solar")
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        f.addView(text(tr("Login करें", "Log in"), 20f, Ui.TEXT, bold = true))
        // the last mobile number used on this phone is filled in, so only the PIN is typed
        val mobile = field(f, tr("Mobile Number", "Mobile number"), prefs.getString("last_mobile", "") ?: "", tr("10 अंकों का number", "10-digit number"), InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13, required = true) {}

        // Four PIN boxes; an invisible field on top takes the typing. Logs in by itself on the 4th digit.
        val boxes = mutableListOf<android.widget.TextView>()
        var showPin = false
        var working = false
        val error = text("", 13f, Ui.DANGER) { visibility = android.view.View.GONE }
        val pin = android.widget.EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(4))
            setTextColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            isCursorVisible = false
            isLongClickable = false
            contentDescription = tr("4 अंकों का PIN", "4-digit PIN")
        }
        fun showBoxes() {
            val t = pin.text.toString()
            boxes.forEachIndexed { i, b ->
                b.text = if (i < t.length) (if (showPin) t[i].toString() else "●") else ""
                b.background = rounded(0xFFF8F5FC.toInt(), dpf(12f), if (pin.hasFocus() && i == t.length.coerceAtMost(3)) Ui.BRAND else 0xFFC8BDD8.toInt(), dp(2))
            }
        }
        fun login() {
            if (working) return
            val m = mobile.text.toString().filter { it.isDigit() }.takeLast(10)
            val p = pin.text.toString().trim()
            if (m.length != 10) { mobile.error = tr("10 अंकों का number डालें", "Enter a 10-digit number"); mobile.requestFocus(); return }
            if (p.length != 4) { error.text = tr("4 अंकों का PIN डालें", "Enter your 4-digit PIN"); error.visibility = android.view.View.VISIBLE; pin.requestFocus(); return }
            working = true
            error.visibility = android.view.View.GONE
            busy(tr("Login हो रहा है…", "Logging in…"), {
                Api.call(this, "login", JSONObject().put("mobile", m).put("pin", p).put("token", "")
                    .put("deviceId", deviceId()).put("appVersion", Updater.currentVersionName(this)))
            }, onError = { e ->
                // wrong PIN: clear the boxes and say why, ready to type again
                working = false
                pin.setText("")
                error.text = e.message ?: tr("Login नहीं हुआ", "Could not log in")
                error.visibility = android.view.View.VISIBLE
                pin.requestFocus()
                true
            }) { o ->
                working = false
                prefs.edit().putString("last_mobile", m).apply()
                val user = User.from(o.getJSONObject("user"))
                val must = o.optBoolean("mustSetPin")
                Session.save(this, o.optString("token"), user, must)
                if (must) showSetPin() else openHome()
            }
        }
        pin.onChange { showBoxes(); if (it.length == 4 && mobile.text.toString().filter { c -> c.isDigit() }.length >= 10) login() }
        pin.setOnFocusChangeListener { _, _ -> showBoxes() }
        pin.setOnEditorActionListener { _, _, _ -> login(); true }

        f.addView(horizontal {
            addView(icon(R.drawable.ic_sign, Ui.BRAND, 16), lp(dp(16), dp(16)).margins(context, r = 6))
            addView(text(req(tr("4 अंकों का PIN", "4-digit PIN")), 13f, Ui.MUTED, bold = true))
        }, lp().margins(this, t = 12, b = 6))
        f.addView(horizontal {
            gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.FrameLayout(context).apply {
                addView(horizontal {
                    repeat(4) { k ->
                        val b = text("", 24f, Ui.TEXT, bold = true) { gravity = Gravity.CENTER }
                        boxes += b
                        addView(b, lp(0, dp(58), 1f).margins(context, r = if (k < 3) 10 else 0))
                    }
                }, android.widget.FrameLayout.LayoutParams(MATCH, dp(58)))
                addView(pin, android.widget.FrameLayout.LayoutParams(MATCH, dp(58)))
            }, lp(0, WRAP, 1f))
            addView(text("👁", 20f, Ui.MUTED) {
                setPadding(dp(12), dp(10), dp(4), dp(10))
                contentDescription = tr("PIN दिखाएँ / छिपाएँ", "Show or hide the PIN")
                setOnClickListener { showPin = !showPin; showBoxes() }
            })
        }, lp())
        f.addView(error, lp().margins(this, t = 8))
        f.addView(pillButton(tr("Login करें →", "Login →"), Ui.SUN, Ui.TEXT, heightDp = 56) { login() }, lp().margins(this, t = 16))
        f.addView(note(tr("AVIP Solar Sales और Installation वाला ही Mobile Number और PIN। एक PIN, तीनों Apps।", "Same mobile number and PIN as AVIP Solar Sales and Installation. One PIN for all three apps.")), lp().margins(this, t = 18))
        f.addView(text(tr("PIN भूल गए? Office से नया temporary PIN लें।", "Forgot your PIN? Ask the office for a new temporary PIN."), 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 4))
        showBoxes()
        if (mobile.text.toString().filter { it.isDigit() }.length >= 10) pin.requestFocus()
    }

    private fun showSetPin() {
        val user = Session.user(this)
        val f = frame(tr("अपना नया PIN बनाएँ", "Set your own PIN"), tr("नमस्ते, ${user?.name ?: ""} · ${Role.label(user?.role ?: "")}", "Hello, ${user?.name ?: ""} · ${Role.label(user?.role ?: "")}"))
        f.addView(text(tr("पहली बार Login पर Office वाला PIN बदलना ज़रूरी है।", "At first login, replace the office PIN with your own."), 15f, Ui.TEXT))
        val numPass = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val pin1 = field(f, tr("नया 4 अंकों का PIN", "New 4-digit PIN"), "", "••••", numPass, R.drawable.ic_sign, 4, required = true) {}
        val pin2 = field(f, tr("PIN दोबारा डालें", "Type the PIN again"), "", "••••", numPass, R.drawable.ic_sign, 4, required = true) {}
        f.addView(vertical {
            listOf(
                tr("1234, 0000, 1111 जैसा आसान PIN नहीं चलेगा", "Easy PINs like 1234, 0000, 1111 are not allowed"),
                tr("Office भी आपका PIN नहीं देख सकता", "Even the office cannot see your PIN"),
                tr("यही PIN Sales और Installation App में भी चलेगा", "The same PIN works in the Sales and Installation apps"),
                tr("भूल गए तो Office नया temporary PIN देगा", "If you forget it, the office gives a new temporary PIN"),
            ).forEach { line ->
                addView(horizontal {
                    addView(text("✓", 14f, Ui.GREEN, bold = true), lp(WRAP, WRAP).margins(context, r = 8))
                    addView(text(line, 14f, Ui.TEXT), lp(0, WRAP, 1f))
                }, lp().margins(context, t = 6))
            }
        }, lp().margins(this, t = 16))
        f.addView(pillButton(tr("PIN Save करें →", "Save PIN →"), Ui.SUN, Ui.TEXT, heightDp = 56) {
            val a = pin1.text.toString().trim()
            val b = pin2.text.toString().trim()
            if (a.length != 4) { pin1.error = tr("4 अंक", "4 digits"); return@pillButton }
            if (a != b) { pin2.error = tr("दोनों PIN एक जैसे नहीं हैं", "The two PINs do not match"); return@pillButton }
            busy(tr("PIN save हो रहा है…", "Saving PIN…"), {
                Api.call(this, "setPin", JSONObject().put("newPin", a))
            }) { o ->
                Session.save(this, o.optString("token"), User.from(o.getJSONObject("user")), false)
                toast(tr("नया PIN save हो गया", "New PIN saved"))
                openHome()
            }
        }, lp().margins(this, t = 22))
        f.addView(text("Logout", 14f, Ui.MUTED, bold = true) {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { Session.clear(this@LoginActivity); showLogin() }
        }, lp())
    }

    @android.annotation.SuppressLint("HardwareIds")
    private fun deviceId(): String = try {
        android.os.Build.MODEL + " · " + (android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "").take(8)
    } catch (_: Exception) { android.os.Build.MODEL }

    private fun openHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}
