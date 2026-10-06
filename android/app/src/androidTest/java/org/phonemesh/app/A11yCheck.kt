package org.phonemesh.app

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.TextView
import org.phonemesh.core.CopyRules

/**
 * The spec's per-screen checks: every clickable view and every list row at least 48 dp and described;
 * no visible text under 18 sp; no visible text over eight words or with a technical word. Text people
 * or radios wrote is marked with Ui.content and exempt from the word rules only. A screen that yields
 * no touch targets, or a list with items but no laid-out rows, is itself a problem.
 */
object A11yCheck {
    /** What a scan looked at, so a test can insist the screen really was reached. */
    data class Scan(val problems: List<String>, val targets: Int, val texts: Int, val rows: Int)

    fun problems(activity: Activity): List<String> = scan(activity).problems

    fun scan(activity: Activity): Scan = TestCore.onMain {
        val metrics = activity.resources.displayMetrics
        val minimumTouch = 47.5f * metrics.density
        val minimumText = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17.5f, metrics)
        val problems = mutableListOf<String>()
        val tally = Tally()
        fun target(view: View, described: Boolean) {
            tally.targets++
            if (view.width < minimumTouch || view.height < minimumTouch) problems += "too small (${view.width}x${view.height} px): ${describe(view)}"
            if (!described) problems += "no description: ${describe(view)}"
        }
        Views.descendants(activity.window.decorView).filter { it.isShown }.forEach { view ->
            if (view is AdapterView<*>) {
                if (view.count > 0 && view.childCount == 0) problems += "list has ${view.count} items but no rows laid out: ${describe(view)}"
                for (i in 0 until view.childCount) {
                    val row = view.getChildAt(i)
                    tally.rows++
                    target(row, !row.contentDescription.isNullOrBlank() || Views.descendants(row).any { it is TextView && it.text.isNotBlank() })
                }
            } else if (view.isClickable || view.isLongClickable) {
                target(view, !view.contentDescription.isNullOrBlank())
            }
            if (view is TextView && view !is EditText && view.text.isNotBlank()) {
                tally.texts++
                val text = view.text.toString()
                if (!Ui.isContent(view)) {
                    if (CopyRules.words(text) > 8) problems += "more than eight words: \"$text\""
                    CopyRules.bannedIn(text).forEach { problems += "technical word '$it': \"$text\"" }
                }
                if (view.textSize < minimumText) problems += "text under 18 sp: \"$text\""
            }
        }
        if (tally.targets == 0) problems += "no touch targets found: the screen was not reached"
        Scan(problems, tally.targets, tally.texts, tally.rows)
    }

    /** A holder, not captured vars: the minified test build lacks kotlin.jvm.internal.Ref. */
    private class Tally { var targets = 0; var texts = 0; var rows = 0 }

    private fun describe(view: View) = "${view.javaClass.simpleName} tag=${view.tag} text=${(view as? TextView)?.text}"
}
