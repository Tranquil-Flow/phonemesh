package org.phonemesh.app

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.phonemesh.core.HowToScene

/**
 * A how-to picture with its one sentence and a speaker. Several scenes play one loop each, in turn, with numbered
 * part buttons to pick one (with animations off, the buttons are the way to see the other parts). [compact] lays
 * the picture beside the words, for tips, notes and the Help page.
 */
class HowToPanel(
    context: Context,
    private val palette: Palette,
    private val scenes: List<HowToScene>,
    private val sentence: (HowToScene) -> String,
    private val canSay: (HowToScene) -> Boolean,
    private val say: (HowToScene) -> Unit,
    compact: Boolean = false,
) : LinearLayout(context) {
    private val picture = HowToView(context, scenes.also { require(it.isNotEmpty()) }.first())
    private val words: TextView = Ui.text(context, "", palette.text, bold = true).apply { tag = "howto_sentence" }
    private val speaker: ImageButton
    private val parts = ArrayList<TextView>()
    var part = 0
        private set

    init {
        require(scenes.isNotEmpty())
        orientation = if (compact) HORIZONTAL else VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val frame = FrameLayout(context)
        frame.addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        speaker = Ui.iconButton(context, palette, R.drawable.ic_speaker, context.getString(R.string.read_aloud), if (compact) 48 else 56).apply {
            tag = "howto_say"
            setOnClickListener { say(scenes[part]) }
        }
        if (compact) {
            addView(frame, LayoutParams(Ui.dp(context, 120), LayoutParams.WRAP_CONTENT))
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(words, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                if (scenes.size > 1) addView(partButtons(context), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
            addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = Ui.dp(context, 12) })
            addView(speaker)
        } else {
            addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(context, 8), 0, 0)
            }
            row.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(speaker)
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            if (scenes.size > 1) addView(partButtons(context), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        picture.onLoop = { if (scenes.size > 1) show((part + 1) % scenes.size) }
        show(0)
    }

    private fun partButtons(context: Context): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        scenes.indices.forEach { i ->
            val button = Ui.text(context, (i + 1).toString(), palette.text, bold = true).apply {
                tag = "part:${i + 1}"
                gravity = Gravity.CENTER
                isClickable = true
                contentDescription = context.getString(R.string.howto_part, i + 1)
                setOnClickListener { show(i) }
            }
            parts += button
            addView(button, LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)).apply { marginEnd = Ui.dp(context, 8) })
        }
    }

    fun show(index: Int) {
        require(index in scenes.indices)
        part = index
        val scene = scenes[index]
        picture.scene = scene
        picture.tag = "howto:${scene.name}"
        words.text = sentence(scene)
        picture.contentDescription = words.text
        parts.forEachIndexed { i, button ->
            val selected = i == index
            button.isSelected = selected
            button.setTextColor(if (selected) palette.onAccent else palette.text)
            button.background = if (selected) Ui.circle(palette.accent) else Ui.circle(palette.surface).apply { setStroke(Ui.dp(context, 2), palette.outline) }
        }
        update()
    }

    /** Shows the speaker only when there is a voice (or a recorded clip) to read the sentence with. */
    fun update() {
        speaker.visibility = if (canSay(scenes[part])) View.VISIBLE else View.GONE
    }
}
