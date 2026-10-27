package org.phonemesh.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import org.phonemesh.core.account.EmojiAvatars

/**
 * Choosing the emoji for your picture (spec decision 49): every emoji all supported phones draw (Android 8.0's), in
 * Unicode's order, or one typed on the keyboard, skin tones included. Returns it as [EXTRA_EMOJI].
 */
class EmojiActivity : ScreenActivity() {
    private lateinit var problem: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), 0)
        }
        column.addView(backBar(getString(R.string.emoji_title)))
        val typed = EditText(this).apply {
            tag = "emoji_type"
            hint = getString(R.string.emoji_type)
            contentDescription = getString(R.string.emoji_type)
            textSize = Ui.BODY_SP
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { view, _, _ -> choose(view.text.toString()); true }
        }
        column.addView(typed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))
        problem = Ui.text(this, getString(R.string.emoji_not_allowed), palette.bad, bold = true).apply {
            tag = "emoji_problem"
            visibility = View.GONE
        }
        column.addView(problem)
        val choices = shown()
        val grid = GridView(this).apply {
            tag = "emoji_grid"
            columnWidth = Ui.dp(this@EmojiActivity, CELL_DP)
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isFastScrollEnabled = true
            adapter = Cells(choices)
            setOnItemClickListener { _, _, position, _ -> choices.getOrNull(position)?.let { done(it) } }
        }
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        show(column)
    }

    /** One per emoji without a skin tone (those are typed), and only those this phone draws. */
    private fun shown(): List<String> = EmojiAvatars.groups.flatMap { it.second }
        .filter { e -> e.codePoints().noneMatch { it in 0x1F3FB..0x1F3FF } && FaceDrawable.canDraw(e) }

    private fun choose(typed: String) {
        val emoji = EmojiAvatars.canonical(typed)?.takeIf { FaceDrawable.canDraw(it) }
        if (emoji == null) { problem.visibility = View.VISIBLE; return }
        done(emoji)
    }

    private fun done(emoji: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_EMOJI, emoji))
        finish()
    }

    override fun render() = Unit

    private inner class Cells(private val items: List<String>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? TextView ?: TextView(this@EmojiActivity).apply {
                gravity = Gravity.CENTER
                textSize = 30f
                // Opaque: an emoji takes the text colour's transparency, and the default one fades it.
                setTextColor(palette.text)
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@EmojiActivity, CELL_DP))
            }
            val emoji = items.getOrNull(position) ?: ""
            cell.text = emoji
            cell.tag = "emoji:$emoji"
            return cell
        }
    }

    companion object {
        const val EXTRA_EMOJI = "emoji"
        private const val CELL_DP = 56

        fun intent(context: Context) = Intent(context, EmojiActivity::class.java)
    }
}
