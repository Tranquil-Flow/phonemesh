package org.phonemesh.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.phonemesh.core.PlanDay
import org.phonemesh.core.PlanWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * "Send later" (owner decision, 2026-10-12): Today and Tomorrow, a calendar button for any other day up to a year
 * ahead, the time between − and + (a quarter hour each), and the time itself opening Android's clock. A time that has
 * passed turns red and Schedule stays off. The speaker reads the choice aloud.
 */
class PlanDialog(
    private val activity: Activity,
    private val palette: Palette,
    private val speak: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val plan: (Long) -> Unit,
) {
    var due: Long = PlanWindow.opening(now(), zone)
        private set
    val dialog: AlertDialog
    /** The Android date or time window opened from this one, for tests. */
    var picker: AlertDialog? = null
        private set

    private val space = Ui.dp(activity, 8)
    private val today = dayButton(R.string.today, "plan_today") { PlanWindow.onDay(due, date(now()), zone) }
    private val tomorrow = dayButton(R.string.tomorrow, "plan_tomorrow") { PlanWindow.onDay(due, date(now()).plusDays(1), zone) }
    private val otherDay = Ui.button(activity, palette, "", R.drawable.ic_calendar).apply {
        tag = "plan_day"
        setOnClickListener { pickDay() }
    }
    private val minus = Ui.button(activity, palette, "−", null, activity.getString(R.string.plan_earlier)).apply {
        tag = "plan_minus"
        textSize = 28f
        setOnClickListener { PlanWindow.step(due, forward = false, now(), zone)?.let { set(it) } }
    }
    private val plus = Ui.button(activity, palette, "+", null, activity.getString(R.string.plan_later)).apply {
        tag = "plan_plus"
        textSize = 28f
        setOnClickListener { PlanWindow.step(due, forward = true, now(), zone)?.let { set(it) } }
    }
    private val sky = ImageView(activity)
    private val time = Ui.text(activity, "", palette.text, 32f, bold = true)
    private val timeButton = LinearLayout(activity).apply {
        tag = "plan_time"
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = Ui.dp(activity, 56)
        isClickable = true
        isFocusable = true
        background = Ui.ripple(Ui.outline(activity, palette.background, palette.outline), Ui.pressedOf(palette.text))
        addView(sky, LinearLayout.LayoutParams(Ui.dp(activity, 30), Ui.dp(activity, 30)).apply { marginEnd = space / 2 })
        addView(time)
        setOnClickListener { pickTime() }
    }
    private val past = Ui.text(activity, activity.getString(R.string.plan_past), palette.bad, bold = true).apply {
        tag = "plan_past"
        gravity = Gravity.CENTER
    }
    private val go = Ui.button(activity, palette, activity.getString(R.string.plan_go), R.drawable.ic_clock, filled = true).apply {
        tag = "plan_go"
        setOnClickListener {
            if (PlanWindow.isPast(due, now())) return@setOnClickListener
            dialog.dismiss()
            plan(due)
        }
    }

    init {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(space * 2, space * 2, space * 2, 0)
        }
        val title = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(activity, activity.getString(R.string.plan_title), palette.text, 22f, bold = true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.iconButton(activity, palette, R.drawable.ic_speaker, activity.getString(R.string.read_aloud)).apply {
                tag = "plan_say"
                setOnClickListener { speak(spoken()) }
            })
        }
        list.addView(title)
        list.addView(row(today to 1f, tomorrow to 1f))
        list.addView(otherDay, full())
        list.addView(row(minus to 0f, timeButton to 1f, plus to 0f))
        list.addView(past, full())
        list.addView(go, full())
        dialog = Actions.readable(AlertDialog.Builder(activity).setView(list).setNegativeButton(R.string.cancel, null).create())
        // Leaving the screen closes this window; a clock or calendar still open over it goes with it.
        dialog.setOnDismissListener { picker?.dismiss() }
        render()
    }

    private fun dayButton(label: Int, tag: String, day: () -> Long) = Ui.button(activity, palette, activity.getString(label), null).apply {
        this.tag = tag
        setOnClickListener { set(day()) }
    }

    private fun row(vararg views: Pair<android.view.View, Float>) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, (view, weight) ->
            val width = if (weight > 0f) 0 else Ui.dp(activity, 64)
            addView(view, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                if (i > 0) marginStart = space
                bottomMargin = space
            })
        }
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = space }

    private fun set(next: Long) {
        due = next
        render()
    }

    private fun date(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun clock(millis: Long): String = DateFormat.getTimeFormat(activity).format(Date(millis))

    private fun dayName(millis: Long): String = Ui.date(activity, millis, "EEEEdMMMM", zone)

    private fun spoken(): String = when (PlanWindow.day(due, now(), zone)) {
        PlanDay.TODAY -> activity.getString(R.string.plan_say_today, clock(due))
        PlanDay.TOMORROW -> activity.getString(R.string.plan_say_tomorrow, clock(due))
        PlanDay.OTHER -> activity.getString(R.string.plan_say_day, dayName(due), clock(due))
    }

    private fun choose(button: Button, chosen: Boolean) {
        button.background = Ui.ripple(Ui.outline(activity, if (chosen) palette.accent else palette.background, palette.outline),
            Ui.pressedOf(palette.text))
        val ink = if (chosen) palette.onAccent else palette.text
        button.setTextColor(ink)
        button.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ink)
        button.isSelected = chosen
    }

    private fun render() {
        val day = PlanWindow.day(due, now(), zone)
        choose(today, day == PlanDay.TODAY)
        choose(tomorrow, day == PlanDay.TOMORROW)
        choose(otherDay, day == PlanDay.OTHER)
        otherDay.text = if (day == PlanDay.OTHER) dayName(due) else activity.getString(R.string.plan_pick_day)
        otherDay.contentDescription = otherDay.text
        val isPast = PlanWindow.isPast(due, now())
        time.text = clock(due)
        time.setTextColor(if (isPast) palette.bad else palette.text)
        sky.setImageResource(if (PlanWindow.isNight(due, zone)) R.drawable.ic_plan_moon else R.drawable.ic_plan_sun)
        timeButton.contentDescription = activity.getString(R.string.plan_time, clock(due))
        past.visibility = if (isPast) android.view.View.VISIBLE else android.view.View.GONE
        Ui.enable(go, !isPast)
        Ui.enable(minus, PlanWindow.step(due, forward = false, now(), zone) != null)
        Ui.enable(plus, PlanWindow.step(due, forward = true, now(), zone) != null)
    }

    /** Android's own calendar, from today to a year ahead. */
    private fun pickDay() {
        val chosen = date(due)
        picker = Ui.inAppLanguage(activity) {
            DatePickerDialog(activity, { _, year, month, dayOfMonth ->
                set(PlanWindow.onDay(due, LocalDate.of(year, month + 1, dayOfMonth), zone))
            }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth)
        }.apply {
            // Each bound is that day's start; the picker compares whole days in the phone's zone.
            datePicker.minDate = date(now()).atStartOfDay(zone).toInstant().toEpochMilli()
            datePicker.maxDate = PlanWindow.lastDay(now(), zone).atStartOfDay(zone).toInstant().toEpochMilli()
            show()
        }
    }

    /** Android's own clock: the hour, then the minutes, 24-hour or 12-hour as the phone is set. */
    private fun pickTime() {
        val at = Instant.ofEpochMilli(due).atZone(zone)
        picker = Ui.inAppLanguage(activity) {
            TimePickerDialog(activity, { _, hour, minute -> set(PlanWindow.atTime(due, hour, minute, zone)) },
                at.hour, at.minute, DateFormat.is24HourFormat(activity))
        }.apply { show() }
    }
}
