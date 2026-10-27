package org.phonemesh.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.phonemesh.core.DeliveryStatus

/** How a delivery status looks: always a shape plus a colour, and a spoken label. */
data class StatusLook(val icon: Int, val color: Int, val label: Int, val showLabel: Boolean)

object Looks {
    fun status(status: DeliveryStatus, palette: Palette): StatusLook = when (status) {
        DeliveryStatus.WAITING -> StatusLook(R.drawable.ic_clock, palette.muted, R.string.status_waiting, false)
        DeliveryStatus.SENT -> StatusLook(R.drawable.ic_sent, palette.text, R.string.status_sent, true)
        DeliveryStatus.HUB -> StatusLook(R.drawable.ic_radio_on, palette.text, R.string.status_hub, true)
        DeliveryStatus.DELIVERED -> StatusLook(R.drawable.ic_check, palette.good, R.string.status_delivered, false)
        DeliveryStatus.UNCONFIRMED -> StatusLook(R.drawable.ic_warning, palette.bad, R.string.status_unconfirmed, true)
        DeliveryStatus.FAILED -> StatusLook(R.drawable.ic_warning, palette.bad, R.string.status_failed, true)
        DeliveryStatus.QUEUED -> StatusLook(R.drawable.ic_clock, palette.muted, R.string.waiting_radio, true)
    }

    /**
     * Group: rounded square, people on yellow. Private: the person's [face] (their colour and their letter or emoji,
     * spec decisions 48-49), or a plain circle with a person when not known.
     */
    fun avatar(context: Context, palette: Palette, kind: ChatKind, sizeDp: Int, face: Face? = null): View = FrameLayout(context).apply {
        val size = Ui.dp(context, sizeDp)
        layoutParams = LinearLayout.LayoutParams(size, size)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        show(this, palette, kind, sizeDp, face)
    }

    /** Draws [kind] and [face] into an [avatar] again (a profile changed while the screen is open). */
    fun show(view: FrameLayout, palette: Palette, kind: ChatKind, sizeDp: Int, face: Face?) {
        val context = view.context
        view.removeAllViews()
        val drawn = if (kind == ChatKind.PERSON && face != null) {
            FaceDrawable(face, palette.outline, Ui.dp(context, 2).toFloat(), FaceDrawable::canDraw)
        } else null
        view.background = when {
            kind == ChatKind.GROUP -> Ui.outline(context, palette.accent, palette.outline, radiusDp = sizeDp / 4)
            drawn != null -> drawn
            else -> Ui.circle(palette.surface).apply { setStroke(Ui.dp(context, 2), palette.outline) }
        }
        if (drawn?.drawn != null) return
        val ink = when {
            kind == ChatKind.GROUP -> palette.onAccent
            face != null -> 0xFF000000.toInt() or org.phonemesh.core.account.ProfileColours.letterOn(face.colour)
            else -> palette.text
        }
        val icon = Ui.dp(context, sizeDp / 2)
        view.addView(Ui.icon(context, if (kind == ChatKind.GROUP) R.drawable.ic_group else R.drawable.ic_person, ink, sizeDp / 2),
            FrameLayout.LayoutParams(icon, icon, Gravity.CENTER))
    }

    /** The people-heard badge of a group: a bold count beside a people icon. Fill it with [showPeople]; the caller sets the tag. */
    fun peopleBadge(context: Context, palette: Palette): TextView = Ui.text(context, "", palette.text, bold = true).apply {
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_group, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(palette.text)
        compoundDrawablePadding = Ui.dp(context, 4)
    }

    /** Shows [heard] people (with a spoken label), or hides the badge when null (a private chat). */
    fun showPeople(view: TextView, heard: Int?) {
        if (heard == null) {
            view.visibility = View.GONE
            return
        }
        view.text = heard.toString()
        view.contentDescription = view.context.getString(R.string.people_heard, heard)
        view.visibility = View.VISIBLE
    }

    /** One delivery-status icon, spoken as its label; the caller sets the tag. */
    fun statusIcon(context: Context, look: StatusLook, sizeDp: Int = 24): ImageView = Ui.icon(context, look.icon, look.color, sizeDp).apply {
        contentDescription = context.getString(look.label)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Radio status: a radio with a green filled dot when connected, a grey hollow dot when not. */
    fun radio(image: ImageView, palette: Palette, connected: Boolean) {
        image.setImageResource(if (connected) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
        image.imageTintList = ColorStateList.valueOf(if (connected) palette.good else palette.muted)
        image.contentDescription = image.context.getString(if (connected) R.string.radio_connected else R.string.radio_not_connected)
    }
}
