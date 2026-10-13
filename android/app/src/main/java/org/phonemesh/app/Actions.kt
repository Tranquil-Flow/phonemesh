package org.phonemesh.app

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout

/** What a long press offers: one large button with a picture per choice, then Cancel. Deleting asks once more. */
object Actions {
    /** [text] replaces the [label] resource when the words carry a value (a time, a name). */
    class Item(val tag: String, val label: Int, val icon: Int, val text: String? = null, val run: () -> Unit)

    fun menu(activity: Activity, palette: Palette, items: List<Item>): AlertDialog {
        val space = Ui.dp(activity, 16)
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(space, space, space, 0)
        }
        val dialog = readable(AlertDialog.Builder(activity).setView(list).setNegativeButton(R.string.cancel, null).create())
        for (item in items) {
            list.addView(Ui.button(activity, palette, item.text ?: activity.getString(item.label), item.icon).apply {
                tag = item.tag
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    dialog.dismiss()
                    item.run()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = space / 2 })
        }
        return dialog
    }

    fun confirm(activity: Activity, title: Int, message: Int, yes: Int, run: () -> Unit): AlertDialog =
        readable(AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> run() }
            .setNegativeButton(R.string.cancel, null)
            .create())

    /** Android draws a dialog's own buttons small; these are read at the app's text size and named for TalkBack. */
    fun readable(dialog: AlertDialog): AlertDialog = dialog.apply {
        setOnShowListener {
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
                getButton(which)?.takeIf { it.text.isNotEmpty() }?.apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.BODY_SP)
                    contentDescription = text
                    minHeight = Ui.dp(context, 48)
                }
            }
        }
    }
}
