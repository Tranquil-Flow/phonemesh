package org.phonemesh.app

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.phonemesh.core.RadioLook
import org.phonemesh.core.RadioLooks
import org.phonemesh.core.Readiness
import org.phonemesh.core.RegionScene
import org.phonemesh.core.RegionStep
import java.util.Locale

/**
 * The region card: on the chats screen as setup step 2's last part or a problem card, on the chat screen while
 * sending is blocked. Its buttons are the only way a region is written: Yes, Change and Try again.
 */
object RegionCards {
    /**
     * The card's sentence, speaker and buttons (tag `card_region`), with [picture] set to match.
     * Null when there is nothing to ask or show.
     */
    fun body(screen: ScreenActivity, palette: Palette, core: AppCore, picture: RegionPicture): View? {
        val region = core.region
        val address = core.settings.radioAddress ?: return null
        val locale = core.currentLanguage().locale
        fun countryName(code: String) = Locale("", code).getDisplayCountry(locale)
        val look = core.radioRegionNow()?.let {
            RadioLooks.of(core.countries, it, core.settings.radioCountry(address), core.settings.helperSet(address))
        }
        fun lookName(): String = when (look) {
            is RadioLook.Country -> countryName(look.code)
            is RadioLook.Area -> look.name
            else -> screen.getString(R.string.region_elsewhere)
        }
        val lookFlag = when (look) {
            is RadioLook.Country -> look.flag
            is RadioLook.Area -> look.flag
            else -> null
        }
        val pad = Ui.dp(screen, 8)
        val lines = ArrayList<String>()
        val buttons = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
        fun button(tagName: String, label: Int, filled: Boolean, enabled: Boolean = true, action: () -> Unit) =
            buttons.addView(Ui.button(screen, palette, screen.getString(label), null, filled = filled).apply {
                tag = tagName
                setOnClickListener { action() }
                Ui.enable(this, enabled)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })

        var done = false
        when (val phase = region.phase) {
            // Nothing to tap while the radio restarts.
            is RegionPhase.Working -> {
                picture.show(RegionScene.WORKING, null, core.countries.flagOf(phase.country))
                lines += screen.getString(R.string.region_working)
            }
            is RegionPhase.Done -> {
                done = true
                picture.show(RegionScene.SET, null, core.countries.flagOf(phase.country))
                lines += if (phase.helper || phase.country == null) screen.getString(R.string.region_ready_helper, phase.region.replace('_', ' '))
                else screen.getString(R.string.region_ready, countryName(phase.country))
            }
            is RegionPhase.Failed -> {
                when (phase.why) {
                    RegionFailure.NO_RETURN -> {
                        picture.show(RegionScene.WORKING, null, lookFlag)
                        lines += screen.getString(R.string.region_no_return)
                        lines += screen.getString(R.string.region_no_return_hint)
                    }
                    RegionFailure.NOT_TAKEN -> {
                        picture.show(RegionScene.HELPER, null, lookFlag)
                        lines += screen.getString(R.string.region_not_taken)
                        lines += screen.getString(R.string.region_ask_helper)
                    }
                    RegionFailure.TRY_AGAIN -> {
                        picture.show(RegionScene.HELPER, null, lookFlag)
                        lines += screen.getString(R.string.region_not_changed)
                    }
                }
                // With the radio away, Try again only reconnects: the card says so until the radio answers or gives up.
                val connecting = core.isConnecting(address)
                if (connecting) lines += screen.getString(R.string.radio_connecting)
                button("region_retry", R.string.region_try_again, filled = true, enabled = !connecting) { region.retry() }
                if (phase.why == RegionFailure.NOT_TAKEN) {
                    button("region_helpers", R.string.region_for_helpers, false) { screen.startActivity(RegionActivity.helpers(screen)) }
                }
                // The way out: the question shows again (Keep, Other country, For helpers). It writes nothing.
                button("region_back", R.string.back, false) { region.back() }
            }
            RegionPhase.Idle -> when (val step = region.step()) {
                RegionStep.Ready -> return null
                is RegionStep.AskSet -> {
                    picture.show(RegionScene.SET, null, core.countries.flagOf(step.country))
                    lines += screen.getString(R.string.region_set, countryName(step.country))
                    button("region_yes", R.string.region_yes, true) { region.yes() }
                    button("region_other", R.string.region_other, false) { screen.startActivity(RegionActivity.countries(screen)) }
                }
                is RegionStep.AskChange -> {
                    picture.show(RegionScene.CHANGE, lookFlag, core.countries.flagOf(step.country))
                    lines += screen.getString(R.string.region_is_set, lookName())
                    lines += screen.getString(R.string.region_change_to, countryName(step.country))
                    button("region_change", R.string.region_change, true) { region.yes() }
                    button("region_keep", R.string.region_keep, false) { region.keep() }
                }
                // The chats screen opens the list by itself once per connection; afterwards this card leads back to it.
                RegionStep.ChooseCountry -> {
                    picture.show(RegionScene.HELPER, null, null)
                    lines += screen.getString(R.string.region_choose_country)
                    button("region_countries", R.string.region_other, true) { screen.startActivity(RegionActivity.countries(screen)) }
                }
                is RegionStep.AskHelper -> {
                    picture.show(RegionScene.HELPER, null, core.countries.flagOf(step.country))
                    lines += step.country?.let { screen.getString(R.string.region_unknown, countryName(it)) }
                        ?: screen.getString(R.string.region_unknown_here)
                    lines += screen.getString(R.string.region_ask)
                    button("region_helpers", R.string.region_for_helpers, false) { screen.startActivity(RegionActivity.helpers(screen)) }
                }
            }
        }
        val sentence = lines.joinToString(" ")
        picture.contentDescription = sentence
        val words = LinearLayout(screen).apply { orientation = LinearLayout.VERTICAL }
        lines.forEachIndexed { i, line ->
            words.addView(Ui.text(screen, line, if (done) palette.good else palette.text, bold = i == 0).apply {
                if (done) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0)
                    compoundDrawableTintList = ColorStateList.valueOf(palette.good)
                    compoundDrawablePadding = pad
                }
            })
        }
        val row = LinearLayout(screen).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad, 0, 0)
            minimumHeight = Ui.dp(screen, 56) + pad
        }
        row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Like the how-to pictures: the speaker shows only when there is a voice to read with.
        if (core.readiness() is Readiness.Ready) {
            row.addView(Ui.iconButton(screen, palette, R.drawable.ic_speaker, screen.getString(R.string.region_listen), 56).apply {
                tag = "region_listen"
                setOnClickListener { core.speakLine(sentence) }
            })
        }
        return LinearLayout(screen).apply {
            orientation = LinearLayout.VERTICAL
            tag = "card_region"
            addView(row)
            addView(buttons)
        }
    }

    /** A whole card, the picture above its words, for a screen without step cards. */
    fun card(screen: ScreenActivity, palette: Palette, core: AppCore, picture: RegionPicture): View? {
        val body = body(screen, palette, core, picture) ?: return null
        return LinearLayout(screen).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Ui.dp(screen, 12)
            setPadding(pad, pad, pad, pad)
            background = Ui.outline(screen, palette.surface, palette.outline)
            (picture.parent as? ViewGroup)?.removeView(picture)
            addView(picture)
            addView(body)
        }
    }
}
