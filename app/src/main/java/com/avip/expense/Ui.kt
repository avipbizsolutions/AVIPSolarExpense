package com.avip.expense

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Small helpers so screens can be built in code with one consistent look. */
object Ui {
    const val BRAND = 0xFF6B3FA0.toInt()
    const val BRAND_DARK = 0xFF4E2C78.toInt()
    const val BRAND_LIGHT = 0xFFF1EBF8.toInt()
    const val SUN = 0xFFF5A524.toInt()
    const val SUN_LIGHT = 0xFFFFF4DE.toInt()
    const val SUN_TEXT = 0xFF8A4500.toInt()
    const val BG = 0xFFF6F4F9.toInt()
    const val TEXT = 0xFF221A30.toInt()
    const val MUTED = 0xFF5A5168.toInt()
    const val LINE = 0xFFE4DEEC.toInt()
    const val DANGER = 0xFFB42318.toInt()
    const val DANGER_LIGHT = 0xFFFDECEA.toInt()
    const val GREEN = 0xFF1B7F4C.toInt()
    const val GREEN_LIGHT = 0xFFE6F4EC.toInt()
    const val TEAL = 0xFF0F766E.toInt()
    const val PURPLE = 0xFF9B2C7E.toInt()
    const val BLUE = 0xFF2B5C8A.toInt()

    /** Colour per tier: Saathi = green, Smart = blue, Executive = gold. */
    fun tierColor(tier: String): Int = when (tier.lowercase()) {
        "saathi" -> 0xFF2E7D32.toInt()
        "smart" -> 0xFF1565C0.toInt()
        "executive" -> 0xFFB7791F.toInt()
        else -> 0xFF546E7A.toInt()
    }
}

/** Standard touch ripple drawn over a view. */
fun Context.ripple(): android.graphics.drawable.Drawable? {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    return ContextCompat.getDrawable(this, tv.resourceId)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun gradient(radius: Float, vararg colors: Int) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

fun Context.vertical(padding: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    block()
}

fun Context.horizontal(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    block()
}

fun Context.text(
    value: CharSequence, size: Float = 14f, color: Int = Ui.TEXT,
    bold: Boolean = false, block: TextView.() -> Unit = {},
) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    block()
}

fun Context.icon(res: Int, tint: Int, sizeDp: Int) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Coloured circle / rounded square with a white icon inside. */
fun Context.badge(res: Int, color: Int, sizeDp: Int = 48, square: Boolean = false): View =
    FrameLayout(this).apply {
        background = if (square) rounded(color, dpf(14f)) else circle(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        addView(ImageView(context).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(sizeDp * 55 / 100), dp(sizeDp * 55 / 100), Gravity.CENTER))
    }

fun Context.card(padding: Int = 14, block: LinearLayout.() -> Unit = {}) = vertical(padding) {
    background = rounded(Color.WHITE, dpf(16f))
    elevation = dpf(2f)
    block()
}

fun Context.pillButton(
    label: String, bg: Int = Ui.BRAND, fg: Int = Color.WHITE, heightDp: Int = 50,
    iconRes: Int = 0, onClick: () -> Unit,
): View = horizontal {
    gravity = Gravity.CENTER
    background = rounded(bg, dpf(heightDp / 2f))
    minimumHeight = dp(heightDp)
    setPadding(dp(18), 0, dp(18), 0)
    isClickable = true
    isFocusable = true
    foreground = context.ripple()
    if (iconRes != 0) addView(icon(iconRes, fg, 20), lp(dp(20), dp(20)).margins(context, r = 8))
    addView(text(label, 15f, fg, bold = true) { gravity = Gravity.CENTER; maxLines = 1 })
    setOnClickListener { onClick() }
}

fun Context.input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, value: String = "", maxLen: Int = 0, required: Boolean = false) =
    EditText(this).apply {
        this.hint = if (required) req(hint) else hint
        inputType = type
        setText(value)
        setTextColor(Ui.TEXT)
        setHintTextColor(0xFF9488A6.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = rounded(Color.WHITE, dpf(12f), 0xFFC8BDD8.toInt(), dp(1))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        if (maxLen > 0) filters = arrayOf(InputFilter.LengthFilter(maxLen))
    }

fun EditText.onChange(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
})

/** Label above an input, with an icon. Returns the EditText; adds both to [parent]. */
/** A label with a red * after it: the field must be filled. */
fun req(label: CharSequence): CharSequence = android.text.SpannableStringBuilder(label).apply {
    val start = length
    append(" *")
    setSpan(android.text.style.ForegroundColorSpan(Ui.DANGER), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

fun Context.field(
    parent: LinearLayout, label: String, value: String, hint: String = "",
    type: Int = InputType.TYPE_CLASS_TEXT, iconRes: Int = 0, maxLen: Int = 0,
    required: Boolean = false,
    onChanged: (String) -> Unit,
): EditText {
    parent.addView(horizontal {
        if (iconRes != 0) addView(icon(iconRes, Ui.BRAND, 16), lp(dp(16), dp(16)).margins(context, r = 6))
        addView(text(if (required) req(label) else label, 13f, Ui.MUTED, bold = true))
    }, lp().margins(this, t = 12, b = 6))
    val e = input(hint, type, value, maxLen)
    e.onChange(onChanged)
    parent.addView(e, lp())
    return e
}

fun Context.sectionTitle(title: String, subtitle: String? = null): View = vertical {
    // a title ending in " *" is a required choice: the * is shown in red
    addView(text(if (title.endsWith(" *")) req(title.dropLast(2)) else title, 17f, Ui.TEXT, bold = true))
    if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12.5f, Ui.MUTED), lp().margins(context, t = 2))
}

/**
 * Row of tappable chips; one can be selected (tap again to clear).
 * Scrolls sideways when the options don't fit.
 */
fun Context.chips(options: List<String>, selected: String, color: Int = Ui.BRAND, onPick: (String) -> Unit): View {
    val row = horizontal { }
    val views = mutableListOf<TextView>()
    var current = selected
    fun paint() = views.forEachIndexed { i, tv ->
        val on = options[i] == current
        tv.background = if (on) rounded(color, dpf(20f)) else rounded(Color.WHITE, dpf(20f), Ui.LINE, dp(1))
        tv.setTextColor(if (on) Color.WHITE else Ui.TEXT)
    }
    options.forEach { opt ->
        val tv = text(opt, 14f, Ui.TEXT, bold = true) {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener {
                current = if (current == opt) "" else opt
                paint(); onPick(current)
            }
        }
        views += tv
        row.addView(tv, lp(WRAP, WRAP).margins(this, r = 8))
    }
    paint()
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
        // lets code select a chip later without rebuilding the screen
        tag = { value: String -> current = value; paint() }
    }
}

/** Grid of big picture tiles (icon + label), single choice. */
fun Context.tiles(
    options: List<Pair<String, Int>>, selected: String, columns: Int = 2, color: Int = Ui.BRAND,
    onPick: (String) -> Unit,
): View {
    val box = vertical { }
    val views = mutableListOf<Pair<LinearLayout, String>>()
    var current = selected
    fun paint() = views.forEach { (v, label) ->
        val on = label == current
        v.background = if (on) rounded(color, dpf(14f)) else rounded(Color.WHITE, dpf(14f), Ui.LINE, dp(1))
        (v.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (on) Color.WHITE else color)
        (v.getChildAt(1) as TextView).setTextColor(if (on) Color.WHITE else Ui.TEXT)
    }
    options.chunked(columns).forEach { chunk ->
        val row = horizontal { }
        chunk.forEach { (label, res) ->
            val tile = vertical {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(14), dp(8), dp(12))
                isClickable = true
                foreground = context.ripple()
                addView(icon(res, color, 30))
                addView(text(label, if (columns >= 4) 12.5f else 13f, Ui.TEXT, bold = true) {
                    gravity = Gravity.CENTER
                    if (columns >= 4) setLines(2) // equal heights even when one label wraps
                }, lp(MATCH, WRAP).margins(context, t = 6))
                setOnClickListener { current = label; paint(); onPick(label) }
            }
            views += tile to label
            row.addView(tile, lp(0, WRAP, 1f).margins(this, r = 8))
        }
        repeat(columns - chunk.size) { row.addView(View(this), lp(0, 1, 1f).margins(this, r = 8)) }
        box.addView(row, lp().margins(this, b = 8))
    }
    paint()
    return box
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(c: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(c.dp(l), c.dp(t), c.dp(r), c.dp(b))
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Green top bar with back arrow, title and optional right-side views. */
fun Activity.topBar(title: String, subtitle: String? = null, showBack: Boolean = true, right: List<View> = emptyList(), onBack: (() -> Unit)? = null): View =
    horizontal {
        background = gradient(0f, Ui.BRAND_DARK, Ui.BRAND)
        setPadding(dp(8), dp(10), dp(12), dp(10))
        minimumHeight = dp(60)
        if (showBack) {
            addView(icon(R.drawable.ic_back, Color.WHITE, 40).apply {
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { if (onBack != null) onBack() else finish() }
            })
        } else {
            addView(View(context), lp(dp(8), 1))
        }
        addView(vertical {
            addView(text(title, 19f, Color.WHITE, bold = true) { maxLines = 1 })
            if (!subtitle.isNullOrBlank()) addView(text(subtitle, 12f, 0xCCFFFFFF.toInt()) { maxLines = 1 })
        }, lp(0, WRAP, 1f).margins(context, l = 4))
        right.forEach { addView(it) }
    }

/** White round icon button for the top bar. */
fun Activity.barButton(res: Int, onClick: () -> Unit): View = icon(res, Color.WHITE, 44).apply {
    setPadding(dp(10), dp(10), dp(10), dp(10))
    foreground = ripple()
    setOnClickListener { onClick() }
}

/** Label : value line used in summaries. */
fun Context.kv(label: String, value: String, bold: Boolean = false): View = horizontal {
    addView(text(label, 13.5f, Ui.MUTED), lp(0, WRAP, 1f))
    addView(text(value, 14f, Ui.TEXT, bold = bold) { gravity = Gravity.END }, lp(0, WRAP, 1.3f))
}.apply { setPadding(0, dp(4), 0, dp(4)) }

/** Small rounded tag like "RECOMMENDED". */
fun Context.tag(label: String, bg: Int, fg: Int = Color.WHITE): TextView = text(label, 10.5f, fg, bold = true) {
    background = rounded(bg, dpf(10f))
    setPadding(dp(8), dp(3), dp(8), dp(3))
    letterSpacing = 0.05f
}

/**
 * Screen language: Hindi-English (default) or English. Chosen on the login and home
 * screens and remembered on the phone.
 */
object Lang {
    @Volatile var english = false
        private set

    fun load(c: Context) {
        english = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("english", false)
    }

    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("english", on).apply()
        english = on
    }
}

/** Text in the chosen language: [hi] is the Hindi-English wording, [en] the English one. */
fun tr(hi: String, en: String): String = if (Lang.english) en else hi

/** Small pill that switches between Hindi-English and English, then redraws the screen. */
fun Activity.langToggle(onDark: Boolean = true): View = text(if (Lang.english) "हिंदी" else "English", 13f,
    if (onDark) Color.WHITE else Ui.TEXT, bold = true) {
    gravity = Gravity.CENTER
    minHeight = dp(36)
    setPadding(dp(12), dp(6), dp(12), dp(6))
    background = rounded(Color.TRANSPARENT, dpf(18f), if (onDark) 0x99FFFFFF.toInt() else Ui.LINE, dp(1))
    contentDescription = if (Lang.english) "Switch to Hindi-English" else "Switch to English"
    setOnClickListener {
        Lang.set(this@langToggle, !Lang.english)
        recreate()
    }
}


/** A highlighted line that scrolls sideways on its own (for an important rule). */
fun Context.marquee(message: String, bg: Int = Ui.SUN_LIGHT, fg: Int = Ui.SUN_TEXT): TextView = TextView(this).apply {
    text = "$message          $message"
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
    setTextColor(fg)
    typeface = Typeface.DEFAULT_BOLD
    isSingleLine = true
    ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
    marqueeRepeatLimit = -1
    setHorizontallyScrolling(true)
    isFocusable = false
    background = rounded(bg, dpf(12f), Ui.SUN, dp(1))
    setPadding(dp(12), dp(10), dp(12), dp(10))
    isSelected = true // starts the scrolling
    contentDescription = message
}
