package com.avip.expense

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Runs [work] in the background with a small "please wait" box, then [done] on
 * the main thread. Errors from the office sheet are shown in plain words; an
 * expired login goes back to the login screen.
 */
fun <T> AppCompatActivity.busy(message: String, work: () -> T, onError: ((Throwable) -> Boolean)? = null, done: (T) -> Unit) {
    val box = horizontal {
        setPadding(dp(22), dp(20), dp(22), dp(20))
        addView(ProgressBar(context), lp(dp(36), dp(36)))
        addView(text(message, 15f, Ui.TEXT), lp(WRAP, WRAP).margins(context, l = 16))
    }
    val dialog = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
    dialog.show()
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { runCatching { work() } }
        if (!isFinishing && !isDestroyed) try { dialog.dismiss() } catch (_: Exception) {}
        result.onSuccess { done(it) }.onFailure { if (onError?.invoke(it) != true) showError(it) }
    }
}

fun Activity.showError(e: Throwable) {
    if (e is ApiException && e.sessionExpired) {
        Session.clear(this)
        Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        return
    }
    if (isFinishing) return
    AlertDialog.Builder(this)
        .setTitle(tr("नहीं हो सका", "Could not complete"))
        .setMessage(e.message ?: "Something went wrong.")
        .setPositiveButton("OK", null)
        .show()
}

fun Activity.toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

fun Activity.confirm(title: String, message: String, yes: String = tr("हाँ", "Yes"), onYes: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton(tr("नहीं", "No"), null)
        .show()
}

/** Asks for one line of text. */
fun Activity.askText(title: String, hint: String, initial: String = "", required: Boolean = true, onOk: (String) -> Unit) {
    val e = input(hint, value = initial, required = required)
    val box = vertical(16) { addView(e, lp()) }
    val d = AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton("OK", null).setNegativeButton(tr("रद्द करें", "Cancel"), null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = e.text.toString().trim()
            if (required && v.isBlank()) { e.error = tr("ज़रूरी है", "Required"); return@setOnClickListener }
            d.dismiss(); onOk(v)
        }
    }
    d.show()
}

fun Activity.pickDate(initialIso: String, onPick: (String) -> Unit) {
    val cal = Calendar.getInstance()
    Fmt.parseIso(initialIso)?.let { cal.timeInMillis = it }
    DatePickerDialog(this, { _, y, m, d ->
        onPick(String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m + 1, d))
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

fun Activity.openUrl(url: String) {
    if (url.isBlank()) return
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast(tr("नहीं खुल सका", "Could not open")) }
}

fun Activity.call(number: String) {
    val n = number.filter { it.isDigit() }
    if (n.isBlank()) return
    try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) } catch (_: Exception) {}
}

/** A coloured rounded label, e.g. the status of a site. */
fun android.content.Context.pill(label: String, bg: Int, fg: Int): TextView = text(label, 12.5f, fg, bold = true) {
    background = rounded(bg, dpf(14f))
    setPadding(dp(10), dp(4), dp(10), dp(4))
    maxLines = 1
}

fun android.content.Context.statusPill(status: String): TextView {
    val (bg, fg) = Status.colors(status)
    return pill(Status.label(status), bg, fg)
}

/** Standard screen: top bar, scrolling body, optional bottom action bar. Returns the body. */
fun Activity.screen(
    title: String, subtitle: String? = null, right: List<View> = emptyList(),
    bottom: View? = null, onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null, onSwipe: ((SwipeRefreshLayout) -> Unit)? = null,
): LinearLayout {
    val body = vertical(16)
    val root = vertical {
        setBackgroundColor(Ui.BG)
        addView(topBar(title, subtitle, right = right, onBack = onBack))
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(body)
        }
        val scrollArea: View = if (onRefresh != null) SwipeRefreshLayout(context).apply {
            setColorSchemeColors(Ui.BRAND)
            addView(scroll)
            setOnRefreshListener { onRefresh() }
            onSwipe?.invoke(this)
        } else scroll
        addView(scrollArea, lp(MATCH, 0, 1f))
        if (bottom != null) addView(vertical {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(14))
            elevation = dpf(8f)
            addView(bottom, lp())
        })
    }
    setContentView(root)
    return body
}

/** White card with a bold heading. */
fun android.content.Context.section(title: String, subtitle: String? = null, block: LinearLayout.() -> Unit): View = card(16) {
    addView(text(if (title.endsWith(" *")) req(title.dropLast(2)) else title, 16f, Ui.TEXT, bold = true))
    if (!subtitle.isNullOrBlank()) addView(text(subtitle, 13f, Ui.MUTED), lp().margins(context, t = 2))
    addView(vertical { block() }, lp().margins(context, t = 8))
}.also { it.layoutParams = lp().margins(this, b = 12) }

/** Small grey hint box. */
fun android.content.Context.note(message: String, bg: Int = Ui.BRAND_LIGHT, fg: Int = Ui.TEXT): View = text(message, 14f, fg) {
    background = rounded(bg, dpf(12f))
    setPadding(dp(12), dp(10), dp(12), dp(10))
    setLineSpacing(0f, 1.15f)
}.also { it.layoutParams = lp().margins(this, b = 12) }

/** Thin "updating" bar shown at the top of a list while fresh data loads. */
fun android.content.Context.updatingBar(): android.view.View = vertical {
    addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
    }, lp(MATCH, dp(6)))
    addView(text(tr("Update हो रहा है…", "Updating…"), 12f, Ui.MUTED), lp().margins(context, b = 6))
}

/** A simple message with an OK button. */
fun Activity.alert(title: String, message: String) {
    androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
}

/**
 * A clear "done" moment: a short tone, a buzz and a big tick with a line of text for about
 * two seconds, then [then] runs. Used when a journey starts and when it is finished.
 */
fun Activity.celebrate(title: String, sub: String, start: Boolean, then: () -> Unit) {
    try {
        val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
        tone.startTone(if (start) android.media.ToneGenerator.TONE_PROP_ACK else android.media.ToneGenerator.TONE_PROP_PROMPT, 500)
        android.os.Handler(mainLooper).postDelayed({ try { tone.release() } catch (_: Exception) {} }, 1200)
    } catch (_: Exception) {}
    try { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) {}
    val box = vertical(28) {
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        addView(android.widget.FrameLayout(context).apply {
            background = circle(Ui.GREEN)
            addView(icon(R.drawable.ic_check, android.graphics.Color.WHITE, 44), android.widget.FrameLayout.LayoutParams(dp(44), dp(44), android.view.Gravity.CENTER))
        }, lp(dp(84), dp(84)))
        addView(text(title, 22f, Ui.TEXT, bold = true) { gravity = android.view.Gravity.CENTER }, lp().margins(context, t = 16))
        if (sub.isNotBlank()) addView(text(sub, 15f, Ui.MUTED) { gravity = android.view.Gravity.CENTER }, lp().margins(context, t = 6))
    }
    val d = AlertDialog.Builder(this).setView(box).setCancelable(false).create()
    try { d.show() } catch (_: Exception) { then(); return }
    box.scaleX = 0.8f; box.scaleY = 0.8f; box.alpha = 0f
    box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start()
    android.os.Handler(mainLooper).postDelayed({
        try { if (!isFinishing && !isDestroyed) d.dismiss() } catch (_: Exception) {}
        then()
    }, 2000)
}

/** Grey, gently pulsing placeholder cards shown while the first load is on its way. */
fun android.content.Context.skeleton(rows: Int = 4): View = vertical {
    val grey = 0xFFE9E4F0.toInt()
    repeat(rows) { i ->
        addView(card(14) {
            addView(horizontal {
                addView(vertical {
                    addView(View(context).apply { background = rounded(grey, dpf(6f)) }, lp(dp(if (i % 2 == 0) 150 else 120), dp(14)))
                    addView(View(context).apply { background = rounded(grey, dpf(6f)) }, lp(dp(if (i % 2 == 0) 200 else 170), dp(11)).margins(context, t = 10))
                }, lp(0, WRAP, 1f))
                addView(View(context).apply { background = rounded(grey, dpf(6f)) }, lp(dp(64), dp(18)))
            })
        }, lp().margins(context, b = 8))
    }
    android.animation.ObjectAnimator.ofFloat(this, "alpha", 1f, 0.45f).apply {
        duration = 750
        repeatMode = android.animation.ValueAnimator.REVERSE
        repeatCount = android.animation.ValueAnimator.INFINITE
        start()
    }
}
