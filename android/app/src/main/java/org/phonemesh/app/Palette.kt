package org.phonemesh.app

import android.content.Context
import android.util.TypedValue

/** The theme's colours (res/values/attrs.xml). 3b's dark theme swaps the theme, not this code. */
class Palette(context: Context) {
    val background = resolve(context, R.attr.pmBackground)
    val surface = resolve(context, R.attr.pmSurface)
    val text = resolve(context, R.attr.pmText)
    val muted = resolve(context, R.attr.pmMuted)
    val outline = resolve(context, R.attr.pmOutline)
    val good = resolve(context, R.attr.pmGood)
    val bad = resolve(context, R.attr.pmBad)
    val accent = resolve(context, R.attr.pmAccent)
    val sent = resolve(context, R.attr.pmSent)

    private fun resolve(context: Context, attr: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attr, value, true)) { "Theme lacks colour attribute $attr" }
        return value.data
    }
}
