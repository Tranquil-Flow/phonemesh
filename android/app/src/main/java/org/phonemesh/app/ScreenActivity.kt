package org.phonemesh.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import org.phonemesh.core.HowToScene
import org.phonemesh.core.HowToScenes

/**
 * Every screen: the chosen language and appearance (not the phone's), FLAG_SECURE, insets, and
 * AppCore observed between onStart and onStop. A screen whose language or appearance changed
 * while it was hidden recreates itself.
 */
abstract class ScreenActivity : Activity(), AppCore.Observer {
    protected val core: AppCore get() = AppCore.get(this)
    protected lateinit var palette: Palette
        private set
    private var appliedLanguage: String? = null
    private var appliedTheme: ThemeMode? = null
    /** The AppCore this screen subscribed to; onStop releases that same one. */
    private var observed: AppCore? = null
    private var noteView: TextView? = null
    private val hideNote = Runnable { noteView?.visibility = View.GONE }

    override fun attachBaseContext(newBase: Context) {
        val settings = AppCore.get(newBase).settings
        appliedLanguage = settings.language
        appliedTheme = settings.theme
        super.attachBaseContext(Themes.wrap(Locales.wrap(newBase, appliedLanguage), settings.theme))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.SECURE_SCREENS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        palette = Palette(this)
        window.decorView.setBackgroundColor(palette.background)
    }

    protected fun pad(): Int = Ui.dp(this, 16)

    /** The top bar's back arrow: the same as the system back. */
    protected fun backButton(): ImageButton = Ui.iconButton(this, palette, R.drawable.ic_back, getString(R.string.back)).apply {
        tag = "back"
        @Suppress("DEPRECATION")
        setOnClickListener { onBackPressed() }
    }

    /** A top bar: back arrow, then [title] in large bold text (none when null). */
    protected fun backBar(title: String?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(this@ScreenActivity, 64)
        addView(backButton())
        if (title != null) addView(Ui.text(this@ScreenActivity, title, palette.text, 24f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() / 2 })
    }

    /** Sets [content] as the screen, with system bars and keyboard as padding. */
    protected fun show(content: View) {
        Ui.applyInsets(content)
        setContentView(content)
        content.requestApplyInsets()
    }

    /** A line for AppCore's short notes; hidden until there is one. */
    protected fun noteLine(): TextView = Ui.text(this, "", palette.text, bold = true).apply {
        tag = NOTE_TAG
        visibility = View.GONE
        setPadding(pad(), pad() / 2, pad(), pad() / 2)
    }.also { noteView = it }

    /** A panel for [scenes] in turn, read aloud with the recorded clip or the app's voice. */
    protected fun howTo(vararg scenes: String, compact: Boolean = false, sentence: ((HowToScene) -> String)? = null): HowToPanel =
        HowToPanel(this, palette, scenes.map { HowToScenes.get(it) },
            sentence = sentence ?: { scene -> getString(HowTo.sentence(scene.name)) },
            canSay = { scene -> core.canSayHowTo(scene.name) },
            say = { scene -> core.sayHowTo(scene.name) },
            compact = compact)

    override fun onStart() {
        super.onStart()
        val current = core
        if (current.settings.language != appliedLanguage || current.settings.theme != appliedTheme) {
            recreate()
            return
        }
        observed = current
        current.activityStarted()
        current.observe(this)
        onScreenStart()
        render()
    }

    override fun onStop() {
        val subscribed = observed
        if (subscribed != null) {
            observed = null
            onScreenStop()
            subscribed.unobserve(this)
            subscribed.activityStopped()
        }
        noteView?.removeCallbacks(hideNote)
        super.onStop()
    }

    override fun onCoreChange(change: AppCore.Change) {
        if (change == AppCore.Change.NOTE) showNote() else render()
    }

    private fun showNote() {
        val view = noteView ?: return
        view.text = core.lastNote?.let { core.text(it) } ?: return
        view.visibility = View.VISIBLE
        view.removeCallbacks(hideNote)
        view.postDelayed(hideNote, NOTE_MS)
    }

    protected open fun onScreenStart() {}
    protected open fun onScreenStop() {}

    /** Rebuilds the screen's changing parts from AppCore. */
    protected abstract fun render()

    companion object {
        const val NOTE_TAG = "note"
        const val NOTE_MS = 5_000L
    }
}
