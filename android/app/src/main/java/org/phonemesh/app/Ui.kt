package org.phonemesh.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Small builders for framework views with the spec's sizes: text at least 18 sp, controls at least 48 dp. */
object Ui {
    const val BODY_SP = 18f
    const val TOUCH_DP = 48

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    /** The app's language (it may differ from the phone's). */
    fun locale(context: Context): java.util.Locale = context.resources.configuration.locales[0]

    /**
     * A date in the app's language, from an Android skeleton ("EEEEdMMMM" gives "jeudi 15 octobre"). Android's own
     * DateFormat.format names days and months in the phone's language, so it is not used for words.
     */
    fun date(context: Context, millis: Long, skeleton: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val locale = locale(context)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return java.time.format.DateTimeFormatter.ofPattern(pattern, locale).format(java.time.Instant.ofEpochMilli(millis).atZone(zone))
    }

    /**
     * Builds one of Android's own windows (date, time) with the app's language as the default for the moment, since
     * those windows read some words from the default language rather than from their context.
     */
    fun <T> inAppLanguage(context: Context, build: () -> T): T {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(locale(context))
        return try { build() } finally { java.util.Locale.setDefault(before) }
    }

    fun outline(context: Context, fill: Int, stroke: Int, radiusDp: Int = 12, strokeDp: Int = 2, dashed: Boolean = false) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (dashed) setStroke(dp(context, strokeDp), stroke, dp(context, 8).toFloat(), dp(context, 6).toFloat())
            else setStroke(dp(context, strokeDp), stroke)
        }

    /** [content] with a translucent [pressed] colour that ripples over it; the content also masks the ripple. */
    fun ripple(content: android.graphics.drawable.Drawable, pressed: Int) =
        RippleDrawable(ColorStateList.valueOf(pressed), content, content)

    fun pressedOf(color: Int) = (color and 0x00FFFFFF) or (0x33 shl 24)

    fun circle(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    fun text(context: Context, value: CharSequence, color: Int, sizeSp: Float = BODY_SP, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            setTextColor(color)
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** Marks text that people or radios wrote (names, messages): exempt from the eight-word check. Leaves the view's tag free. */
    fun content(view: TextView): TextView = view.apply { setTag(R.id.ui_content, true) }

    fun isContent(view: View): Boolean = view.getTag(R.id.ui_content) == true

    fun icon(context: Context, drawable: Int, color: Int, sizeDp: Int = 24) = ImageView(context).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun iconButton(context: Context, palette: Palette, drawable: Int, description: String, sizeDp: Int = TOUCH_DP, color: Int = palette.text) =
        ImageButton(context).apply {
            setImageResource(drawable)
            imageTintList = ColorStateList.valueOf(color)
            contentDescription = description
            // Unbounded ripple: no content, so the pressed circle is drawn at the view's size.
            background = RippleDrawable(ColorStateList.valueOf(pressedOf(palette.text)), null, circle(Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER
            val size = dp(context, maxOf(sizeDp, TOUCH_DP))
            minimumWidth = size
            minimumHeight = size
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

    fun button(context: Context, palette: Palette, label: String, drawable: Int?, description: String = label, filled: Boolean = false) =
        Button(context).apply {
            text = label
            contentDescription = description
            isAllCaps = false
            textSize = BODY_SP
            setTypeface(typeface, Typeface.BOLD)
            val foreground = if (filled) palette.background else palette.text
            setTextColor(foreground)
            minHeight = dp(context, 56)
            minimumHeight = dp(context, 56)
            // Filled buttons are dark, so their press shows in the light colour.
            background = ripple(outline(context, if (filled) palette.text else palette.background, palette.outline),
                pressedOf(if (filled) palette.background else palette.text))
            stateListAnimator = null
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
            if (drawable != null) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, 0, 0, 0)
                compoundDrawablePadding = dp(context, 8)
                compoundDrawableTintList = ColorStateList.valueOf(foreground)
            }
        }

    fun enable(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    /** System bars and keyboard become padding on top of whatever padding the view already had. */
    fun applyInsets(view: View) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(left + insets.systemWindowInsetLeft, top + insets.systemWindowInsetTop, right + insets.systemWindowInsetRight, bottom + insets.systemWindowInsetBottom)
            }
            insets
        }
    }
}

/** Every icon the UI draws (ThemeTest loads each one). */
object Icons {
    val all = listOf(
        R.drawable.ic_auto, R.drawable.ic_back, R.drawable.ic_chat, R.drawable.ic_check, R.drawable.ic_chevron,
        R.drawable.ic_clock, R.drawable.ic_close, R.drawable.ic_download, R.drawable.ic_folder, R.drawable.ic_gear,
        R.drawable.ic_globe, R.drawable.ic_group, R.drawable.ic_help, R.drawable.ic_hint_tail, R.drawable.ic_keyboard, R.drawable.ic_lock, R.drawable.ic_mic,
        R.drawable.ic_moon, R.drawable.ic_no_signal, R.drawable.ic_pause, R.drawable.ic_person, R.drawable.ic_picture, R.drawable.ic_play,
        R.drawable.ic_plus, R.drawable.ic_radio_off, R.drawable.ic_radio_on, R.drawable.ic_retry, R.drawable.ic_search,
        R.drawable.ic_send, R.drawable.ic_sent, R.drawable.ic_speaker, R.drawable.ic_stop, R.drawable.ic_sun,
        R.drawable.ic_warning, R.drawable.ic_calendar, R.drawable.ic_plan_sun, R.drawable.ic_plan_moon, R.drawable.ic_camera,
    )
}
