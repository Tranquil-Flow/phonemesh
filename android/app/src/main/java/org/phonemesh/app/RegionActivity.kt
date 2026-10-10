package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import org.phonemesh.ble.RadioRegions
import org.phonemesh.core.FlagSpec
import org.phonemesh.core.Readiness
import org.phonemesh.core.RegionCheck
import java.util.Locale

/**
 * Two lists for the connected radio. Countries: the supported countries with their flags, then "My country is not
 * here"; choosing one only tells the region check, which then asks. For helpers: every region the app may write;
 * Set stays off until a region is chosen and the box is ticked, and is the only thing here that writes.
 */
class RegionActivity : ScreenActivity() {
    private var helpers = false
    private var chosen: String? = null
    private val rows = Rows()
    private var tick: CheckBox? = null
    private var set: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helpers = intent.getStringExtra(EXTRA_MODE) == MODE_HELPERS
        chosen = savedInstanceState?.getString(STATE_CHOSEN)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(if (helpers) R.string.helper_title else R.string.region_choose_country)))
        column.addView(noteLine())
        if (helpers) column.addView(Ui.text(this, getString(R.string.helper_line), palette.text).apply { setPadding(0, 0, 0, pad() / 2) })
        rows.items = if (helpers) RadioRegions.all else {
            val locale = core.currentLanguage().locale
            core.countries.countries.keys.sortedBy { Locale("", it).getDisplayCountry(locale) } + null
        }
        column.addView(ListView(this).apply {
            tag = if (helpers) "regions" else "countries"
            adapter = rows
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            itemsCanFocus = true
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (helpers) {
            val country = core.region.country()?.takeIf { it != RegionCheck.NOT_LISTED }
            tick = CheckBox(this).apply {
                tag = "helper_tick"
                text = country?.let { getString(R.string.helper_tick, countryName(it)) } ?: getString(R.string.helper_tick_here)
                contentDescription = text
                textSize = Ui.BODY_SP
                setTextColor(palette.text)
                buttonTintList = ColorStateList.valueOf(palette.text)
                minHeight = Ui.dp(this@RegionActivity, 56)
                minimumHeight = Ui.dp(this@RegionActivity, 56)
                isChecked = savedInstanceState?.getBoolean(STATE_TICKED) == true
                setOnCheckedChangeListener { _, _ -> render() }
            }
            column.addView(tick, full().apply { topMargin = pad() / 2 })
            set = Ui.button(this, palette, getString(R.string.helper_set), R.drawable.ic_check, filled = true).apply {
                tag = "helper_set"
                setOnClickListener {
                    val region = chosen
                    if (isEnabled && region != null) {
                        core.region.helperChoose(region)
                        finish()
                    }
                }
            }
            column.addView(set, full().apply { topMargin = pad() / 2 })
        }
        show(column)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CHOSEN, chosen)
        outState.putBoolean(STATE_TICKED, tick?.isChecked == true)
    }

    /** Without a radio there is nothing to set. */
    override fun render() {
        if (!core.connected) { finish(); return }
        rows.notifyDataSetChanged()
        // Set needs a region and the tick, and waits while another region write is running.
        set?.let { Ui.enable(it, chosen != null && tick?.isChecked == true && core.region.phase !is RegionPhase.Working) }
    }

    private fun countryName(code: String) = Locale("", code).getDisplayCountry(core.currentLanguage().locale)

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private inner class Rows : BaseAdapter() {
        /** Region names, or country codes with null last for "My country is not here". */
        var items: List<String?> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            if (helpers) regionRow(checkNotNull(items[position])) else countryRow(items[position])
    }

    /** Flag, then the name with its chevron (chooses), then the speaker (only says the name). */
    private fun countryRow(code: String?): View {
        val name = code?.let { countryName(it) } ?: getString(R.string.region_not_here)
        val row = row()
        row.addView(FlagView(this, core.countries.flagOf(code), palette.outline), flagSize())
        row.addView(Ui.button(this, palette, name, null).apply {
            tag = code?.let { "country:$it" } ?: "country_not_here"
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron, 0)
            compoundDrawableTintList = ColorStateList.valueOf(palette.text)
            setOnClickListener {
                core.region.choose(code ?: RegionCheck.NOT_LISTED)
                finish()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() / 2 })
        if (core.readiness() is Readiness.Ready) {
            row.addView(Ui.iconButton(this, palette, R.drawable.ic_speaker, getString(R.string.hear_name, name), 56).apply {
                tag = code?.let { "say:$it" } ?: "say_not_here"
                setOnClickListener { core.speakLine(name) }
            })
        }
        return row
    }

    /** One region: its flag (a globe when it has none) and its name; the chosen one is marked. */
    private fun regionRow(region: String): View {
        val selected = region == chosen
        val name = region.replace('_', ' ')
        val line = core.countries.regions[region]
        val row = row().apply {
            tag = "region:$region"
            contentDescription = name
            isClickable = true
            isFocusable = true
            // Activated, not selected: a ListView overwrites its rows' selected state.
            isActivated = selected
            background = Ui.ripple(Ui.outline(this@RegionActivity, if (selected) palette.accent else palette.background,
                if (selected) palette.outline else palette.background), Ui.pressedOf(palette.text))
            setOnClickListener {
                chosen = region
                render()
            }
        }
        row.addView(FlagView(this, line?.flagOf?.let { core.countries.flagOf(it) } ?: line?.flag, palette.outline), flagSize())
        row.addView(Ui.text(this, name, if (selected) palette.onAccent else palette.text, bold = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        if (selected) row.addView(Ui.icon(this, R.drawable.ic_check, palette.onAccent, 32))
        return row
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(this@RegionActivity, 64)
        setPadding(pad() / 2, pad() / 4, pad() / 2, pad() / 4)
    }

    private fun flagSize() = LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 32))

    /** A small flag drawn from its description, with a thin edge so pale stripes show on any background. */
    private class FlagView(context: Context, private val flag: FlagSpec?, edge: Int) : View(context) {
        private val box = RectF()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = edge
            strokeWidth = Ui.dp(context, 1).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            FlagPainter.draw(canvas, flag, box)
            if (flag != null) canvas.drawRect(box, line)
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val MODE_COUNTRIES = "countries"
        private const val MODE_HELPERS = "helpers"
        private const val STATE_CHOSEN = "chosen"
        private const val STATE_TICKED = "ticked"

        fun countries(context: Context): Intent = Intent(context, RegionActivity::class.java).putExtra(EXTRA_MODE, MODE_COUNTRIES)

        fun helpers(context: Context): Intent = Intent(context, RegionActivity::class.java).putExtra(EXTRA_MODE, MODE_HELPERS)
    }
}
