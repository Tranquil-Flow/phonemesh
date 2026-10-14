package org.phonemesh.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar

/** How the remembered radio is: linked, being linked (the app reconnects by itself when it opens), or not. */
enum class RadioLink { CONNECTED, CONNECTING, OFF }

/** One round mark for the radio, the same on every screen. Its shape says it as well as its colour. */
object RadioBadge {
    fun linkOf(core: AppCore): RadioLink = when {
        core.connected -> RadioLink.CONNECTED
        core.settings.radioAddress?.let { core.isConnecting(it) } == true -> RadioLink.CONNECTING
        else -> RadioLink.OFF
    }

    /** Green with a tick, grey with a turning ring, or red with a cross; the words beside it say the same. */
    fun view(context: Context, palette: Palette, link: RadioLink, sizeDp: Int): View = FrameLayout(context).apply {
        tag = "radio_badge:${link.name.lowercase()}"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val icon = sizeDp / 2
        fun centered(dp: Int) = FrameLayout.LayoutParams(Ui.dp(context, dp), Ui.dp(context, dp), Gravity.CENTER)
        when (link) {
            RadioLink.CONNECTED -> {
                background = Ui.circle(palette.good)
                addView(Ui.icon(context, R.drawable.ic_check, palette.background, icon), centered(icon))
            }
            RadioLink.CONNECTING -> {
                background = Ui.circle(palette.surface).apply { setStroke(Ui.dp(context, 3), palette.muted) }
                addView(ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(palette.muted)
                }, centered(sizeDp * 2 / 3))
            }
            RadioLink.OFF -> {
                background = Ui.circle(palette.bad)
                addView(Ui.icon(context, R.drawable.ic_close, palette.background, icon), centered(icon))
            }
        }
    }

    /** The radio's name for these sentences: its own name, or "Radio EE01". */
    fun name(core: AppCore): String = core.ownRadioLabel(core.settings.radioName, core.settings.radioAddress)
}
