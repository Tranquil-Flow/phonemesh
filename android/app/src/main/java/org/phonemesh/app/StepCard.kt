package org.phonemesh.app

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import org.phonemesh.core.SetupStep
import org.phonemesh.core.SetupSteps

/** The setup guide's large current step, slim numbered rows, and unnumbered problem cards. */
object StepCard {
    private val TOTAL = SetupSteps.ORDER.size

    fun tag(step: SetupStep) = when (step) {
        SetupStep.VOICE -> "card_voice"
        SetupStep.NAME -> "card_name"
        SetupStep.RADIO -> "card_radio"
        SetupStep.HELLO -> "card_hello"
    }

    /** The step's how-to scenes in order; pairing stays off pending real-radio evidence. */
    fun scenes(step: SetupStep): List<String> = when (step) {
        SetupStep.VOICE -> listOf("download", "friend")
        // Holding the microphone and speaking, as for hello; a picture of its own waits for the how-to review (G4).
        SetupStep.NAME -> listOf("hello")
        SetupStep.RADIO -> listOf("power", "close", "search")
        SetupStep.HELLO -> listOf("hello")
    }

    fun problemScene(step: SetupStep): String = when (step) {
        SetupStep.RADIO -> "lost"
        SetupStep.NAME -> "hello"
        else -> "download"
    }

    fun current(context: Context, palette: Palette, step: SetupStep, done: Int, title: String, picture: View, detail: String?, actions: View): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = tag(step)
            val pad = Ui.dp(context, 16)
            setPadding(pad, pad, pad, pad)
            background = Ui.outline(context, palette.surface, palette.outline, radiusDp = 16)
            layoutParams = full().apply { topMargin = pad / 2 }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(circle(context, palette, step, done = false, current = true, sizeDp = 48))
            val words = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 12), 0, 0, 0)
            }
            words.addView(Ui.text(context, context.getString(R.string.step_of, SetupSteps.number(step), TOTAL), palette.muted, bold = true)
                .apply { tag = "step_of" })
            words.addView(Ui.text(context, title, palette.text, 22f, bold = true))
            header.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(header)
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "step_progress"
                max = TOTAL
                progress = done
                progressTintList = ColorStateList.valueOf(palette.good)
                progressBackgroundTintList = ColorStateList.valueOf(palette.outline)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, full().apply { topMargin = Ui.dp(context, 12) })
            addView(detach(picture), full().apply { topMargin = Ui.dp(context, 12) })
            if (detail != null) addView(Ui.text(context, detail, palette.text), full().apply { topMargin = Ui.dp(context, 8) })
            addView(actions, full())
        }

    /** Other steps show a green check when done, or a grey number and hint. */
    fun row(context: Context, palette: Palette, step: SetupStep, done: Boolean, title: String, detail: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "step_row:${SetupSteps.number(step)}"
            minimumHeight = Ui.dp(context, 56)
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
            layoutParams = full()
            addView(circle(context, palette, step, done, current = false, sizeDp = 36))
            val words = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 12), 0, 0, 0)
            }
            words.addView(Ui.text(context, title, if (done) palette.text else palette.muted, bold = true))
            words.addView(Ui.text(context, detail, palette.muted))
            addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    /** After setup, lost voices and radios show their picture without step numbers. */
    fun problem(context: Context, palette: Palette, step: SetupStep, title: String, picture: View, actions: View): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = tag(step)
            val pad = Ui.dp(context, 12)
            setPadding(pad, pad, pad, pad)
            background = Ui.outline(context, palette.surface, palette.outline)
            layoutParams = full().apply { topMargin = Ui.dp(context, 8) }
            addView(Ui.text(context, title, palette.text, bold = true))
            addView(detach(picture), full().apply { topMargin = Ui.dp(context, 8) })
            addView(actions, full())
        }

    /** A kept panel leaves its previous card before joining the new one. */
    private fun detach(view: View): View = view.also { (it.parent as? ViewGroup)?.removeView(it) }

    private fun circle(context: Context, palette: Palette, step: SetupStep, done: Boolean, current: Boolean, sizeDp: Int): View =
        FrameLayout(context).apply {
            val size = Ui.dp(context, sizeDp)
            layoutParams = LinearLayout.LayoutParams(size, size)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            when {
                done -> {
                    tag = "step_done:${SetupSteps.number(step)}"
                    background = Ui.circle(palette.good)
                    addView(Ui.icon(context, R.drawable.ic_check, palette.background, sizeDp / 2),
                        FrameLayout.LayoutParams(Ui.dp(context, sizeDp / 2), Ui.dp(context, sizeDp / 2), Gravity.CENTER))
                }
                current -> {
                    background = Ui.circle(palette.accent).apply { setStroke(Ui.dp(context, 2), palette.outline) }
                    addView(Ui.text(context, SetupSteps.number(step).toString(), palette.onAccent, 22f, bold = true).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                else -> {
                    background = Ui.circle(palette.surface).apply { setStroke(Ui.dp(context, 2), palette.muted) }
                    addView(Ui.text(context, SetupSteps.number(step).toString(), palette.muted, bold = true).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }
        }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
