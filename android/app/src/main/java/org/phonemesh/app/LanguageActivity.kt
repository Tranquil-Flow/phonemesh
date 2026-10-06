package org.phonemesh.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** First launch (and Settings → Langue): one large button per language, each saying its own name. */
class LanguageActivity : ScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad() * 2, pad() * 2, pad() * 2, pad() * 2)
        }
        root.addView(Ui.icon(this, R.drawable.ic_globe, palette.text, 96).apply {
            contentDescription = getString(R.string.language_title)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        })
        LanguageRegistry.all.forEach { language ->
            val button = Ui.button(this, palette, language.endonym, R.drawable.ic_speaker).apply {
                tag = "language:${language.tag}"
                textSize = 28f
                minHeight = Ui.dp(this@LanguageActivity, 88)
                setOnClickListener { choose(language) }
            }
            root.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad() * 2 })
        }
        show(root)
    }

    /** Speaks the name (AppCore's speech outlives this screen) and continues at once. */
    private fun choose(language: Language) {
        core.speech.playClip(language.clip) {}
        core.settings.language = language.tag
        setResult(RESULT_OK)
        finish()
    }

    override fun render() = Unit

    @Deprecated("Platform callback")
    override fun onBackPressed() {
        // On first launch there is nothing behind this screen worth showing without a language.
        if (core.settings.language == null) finishAffinity() else @Suppress("DEPRECATION") super.onBackPressed()
    }
}
