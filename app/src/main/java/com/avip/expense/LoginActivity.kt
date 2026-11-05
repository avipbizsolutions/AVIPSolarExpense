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
        f.addView(text(tr("Login करें", "Log in"), 20f, Ui.TEXT, bold = true))
        val mobile = field(f, "Mobile Number", "", tr("10 अंकों का number", "10-digit number"), InputType.TYPE_CLASS_PHONE, R.drawable.ic_call, 13, required = true) {}
        val pin = field(f, tr("4 अंकों का PIN", "4-digit PIN"), "", "••••", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, R.drawable.ic_sign, 4, required = true) {}
        f.addView(pillButton("Login →", Ui.SUN, Ui.TEXT, heightDp = 56) {
            val m = mobile.text.toString().filter { it.isDigit() }.takeLast(10)
            val p = pin.text.toString().trim()
            if (m.length != 10) { mobile.error = tr("10 अंकों का number डालें", "Enter a 10-digit number"); return@pillButton }
            if (p.length != 4) { pin.error = tr("4 अंकों का PIN", "4-digit PIN"); return@pillButton }
            busy(tr("Login हो रहा है…", "Logging in…"), {
                Api.call(this, "login", JSONObject().put("mobile", m).put("pin", p).put("token", "")
                    .put("deviceId", deviceId()).put("appVersion", Updater.currentVersionName(this)))
            }) { o ->
                val user = User.from(o.getJSONObject("user"))
                val must = o.optBoolean("mustSetPin")
                Session.save(this, o.optString("token"), user, must)
                if (must) showSetPin() else openHome()
            }
        }, lp().margins(this, t = 20))
        f.addView(note(tr("AVIP Solar Sales और Installation वाला ही Mobile Number और PIN। एक PIN, तीनों Apps।", "Same mobile number and PIN as AVIP Solar Sales and Installation. One PIN for all three apps.")), lp().margins(this, t = 18))
        f.addView(text(tr("PIN भूल गए? Office से नया temporary PIN लें।", "Forgot your PIN? Ask the office for a new temporary PIN."), 13f, Ui.MUTED) { gravity = Gravity.CENTER }, lp().margins(this, t = 4))
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
