package org.phonemesh.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.phonemesh.core.StoryTimeline

/** The story's number (or no-signal sign), sentence, speaker and five moment dots. */
class StoryCaption(context: Context, private val palette: Palette, private val onSpeak: (index: Int) -> Unit) : LinearLayout(context) {
    private val number: TextView
    private val noSignal: ImageView
    private val sentence: TextView
    private val dots = ArrayList<View>()
    private var index = 0

    init {
        orientation = VERTICAL
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = FrameLayout(context).apply { background = Ui.circle(palette.accent) }
        number = Ui.text(context, "", palette.onAccent, 20f, bold = true).apply {
            tag = "caption_number"
            gravity = Gravity.CENTER
        }
        noSignal = Ui.icon(context, R.drawable.ic_no_signal, palette.onAccent, 24)
        badge.addView(number, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        badge.addView(noSignal, FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24), Gravity.CENTER))
        row.addView(badge, LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)))
        sentence = Ui.text(context, "", palette.text, bold = true).apply { tag = "caption" }
        row.addView(sentence, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Ui.dp(context, 12)
            marginEnd = Ui.dp(context, 8)
        })
        row.addView(Ui.iconButton(context, palette, R.drawable.ic_speaker, context.getString(R.string.read_aloud), 56).apply {
            tag = "caption_speak"
            setOnClickListener { onSpeak(index) }
        })
        addView(row)
        val dotRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 8), 0, 0)
        }
        repeat(StoryTimeline.MOMENTS) { i ->
            val dot = View(context).apply {
                tag = "dot:$i"
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            dots += dot
            dotRow.addView(dot, LayoutParams(Ui.dp(context, 10), Ui.dp(context, 10)).apply {
                marginStart = Ui.dp(context, 4)
                marginEnd = Ui.dp(context, 4)
            })
        }
        addView(dotRow)
    }

    /** [moment] is -1 for the opening/closing line, or 0..4 for the numbered moments. */
    fun show(moment: Int) {
        index = StoryCaptions.index(moment)
        sentence.text = context.getString(StoryCaptions.TEXT[index])
        number.text = if (moment < 0) "" else (moment + 1).toString()
        noSignal.visibility = if (moment < 0) VISIBLE else GONE
        dots.forEachIndexed { i, dot ->
            dot.background = if (i == moment) Ui.circle(palette.text)
            else Ui.circle(palette.background).apply { setStroke(Ui.dp(context, 2), palette.outline) }
        }
    }
}
