package org.phonemesh.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * First launch (and Settings → Language): the welcome story, then a row per language.
 * The speaker only says the name; the name with its chevron chooses it.
 */
class LanguageActivity : ScreenActivity() {
    private lateinit var story: StoryView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chosen = core.settings.language
        val captions = LanguageRegistry.forCaptions(chosen, resources.configuration.locales[0])
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), pad() * 2)
        }
        if (chosen != null) column.addView(backBar(null))
        column.addView(Ui.text(this, getString(R.string.app_name), palette.text, 32f, bold = true))
        column.addView(Ui.text(this, getString(R.string.tagline), palette.muted))
        story = StoryView(this).apply {
            tag = "story"
            contentDescription = getString(R.string.caption_opening)
        }
        column.addView(story, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = pad() })
        val caption = StoryCaption(this, palette) { index -> core.speech.playClip(captions.captionClips[index]) {} }
        story.onMoment = { caption.show(it) }
        caption.show(story.moment)
        column.addView(caption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = pad() })
        val languages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = getString(R.string.language_title)
        }
        LanguageRegistry.all.forEach { languages.addView(languageRow(it, it.tag == chosen)) }
        column.addView(languages)
        show(ScrollView(this).apply { addView(column) })
    }

    private fun languageRow(language: Language, chosen: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad() * 3 / 2 }
        }
        val speakerSize = Ui.dp(this, SPEAKER_DP)
        row.addView(Ui.iconButton(this, palette, R.drawable.ic_speaker, getString(R.string.hear_name, language.endonym), SPEAKER_DP, palette.onAccent).apply {
            tag = "speak:${language.tag}"
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = Ui.dp(this@LanguageActivity, 18)
            setPadding(inset, inset, inset, inset)
            background = Ui.ripple(Ui.outline(this@LanguageActivity, palette.accent, palette.outline, radiusDp = 16), Ui.pressedOf(palette.text))
            setOnClickListener { core.speech.playClip(language.clip) {} }
        }, LinearLayout.LayoutParams(speakerSize, speakerSize))
        row.addView(Ui.button(this, palette, language.endonym, null).apply {
            tag = "language:${language.tag}"
            textSize = 28f
            minHeight = Ui.dp(this@LanguageActivity, 88)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isSelected = chosen
            setCompoundDrawablesRelativeWithIntrinsicBounds(if (chosen) R.drawable.ic_check else 0, 0, R.drawable.ic_chevron, 0)
            compoundDrawablePadding = Ui.dp(this@LanguageActivity, 8)
            compoundDrawableTintList = ColorStateList.valueOf(palette.text)
            if (chosen) background = Ui.ripple(Ui.outline(this@LanguageActivity, palette.surface, palette.good, strokeDp = 3), Ui.pressedOf(palette.text))
            setOnClickListener { choose(language) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        return row
    }

    /** Speaks the name (AppCore's speech outlives this screen) and continues at once. */
    private fun choose(language: Language) {
        core.speech.playClip(language.clip) {}
        core.settings.language = language.tag
        setResult(RESULT_OK)
        finish()
    }

    override fun onScreenStart() = story.start()

    override fun onScreenStop() = story.stop()

    override fun render() = Unit

    @Deprecated("Platform callback")
    override fun onBackPressed() {
        // On first launch there is nothing behind this screen worth showing without a language.
        if (core.settings.language == null) finishAffinity() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private companion object {
        const val SPEAKER_DP = 72
    }
}
