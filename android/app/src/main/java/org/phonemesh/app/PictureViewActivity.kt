package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * One picture, large: its exact pixels, smoothed, sharper (no AI), or enlarged by an AI model on this phone, with how
 * long each took. AI views say they may invent detail. The view shown can be saved to Photos or shared. Nothing here
 * uses the radio.
 */
class PictureViewActivity : ScreenActivity() {
    private val messageId by lazy { intent.getLongExtra(EXTRA_MESSAGE, -1L) }
    private var source: Bitmap? = null
    private var look = PictureLook.SMOOTH
    private val made = HashMap<PictureLook, PictureLooks.Made>()
    private var job = 0
    /** The view being made; a newer choice, or leaving the screen, cancels it. */
    private var running: PictureLooks.Job? = null
    private lateinit var image: ImageView
    private lateinit var info: TextView
    private lateinit var aiNote: TextView
    private lateinit var looks: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var shareButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var saved: TextView
    private lateinit var seeButton: Button
    private var savedUri: android.net.Uri? = null
    private lateinit var inChat: TextView
    private lateinit var useButton: Button
    private lateinit var originalButton: Button

    /** The view the chat shows, or null for the picture as it came (which looks like [PictureLook.SMOOTH]). */
    private fun chosen(): PictureLook? = core.chosenLook(messageId)?.let { key -> PictureLook.entries.firstOrNull { it.key == key } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = core.pictureBitmap(messageId)
        if (source == null) { finish(); return }
        // It opens on the view the chat shows; the chat keeps only a bubble-size copy, so the full size is made again.
        chosen()?.let { look = it }
        savedInstanceState?.getString(STATE_LOOK)?.let { saved -> PictureLook.entries.firstOrNull { it.name == saved }?.let { look = it } }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.picture_label)))
        column.addView(noteLine())
        image = ImageView(this).apply {
            tag = "view_image"
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.picture_label)
            background = Ui.outline(this@PictureViewActivity, palette.surface, palette.outline)
        }
        column.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 300)))
        // A bar while a view is made: it fills piece by piece for the large models, and moves without end while one
        // step (loading a model, a whole small picture) has no parts to count.
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "view_progress"
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(palette.good)
            visibility = View.GONE
        }
        column.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 12)).apply {
            topMargin = pad() / 2
        })
        info = Ui.text(this, "", palette.text, bold = true).apply {
            tag = "view_info"
            setPadding(0, pad() / 2, 0, 0)
        }
        column.addView(info)
        aiNote = Ui.text(this, getString(R.string.look_ai_note), palette.bad, bold = true).apply { tag = "view_ai_note" }
        column.addView(aiNote)
        looks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad() / 2, 0, 0) }
        column.addView(looks)
        if (PictureLook.entries.any { !PictureLooks.available(this, it) }) {
            column.addView(Ui.text(this, getString(R.string.look_missing), palette.muted).apply { tag = "view_missing" })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad(), 0, 0) }
        saveButton = Ui.button(this, palette, getString(R.string.picture_save), R.drawable.ic_check, filled = true).apply {
            tag = "view_save"
            setOnClickListener { save() }
            visibility = if (PictureExport.canSave) View.VISIBLE else View.GONE
        }
        shareButton = Ui.button(this, palette, getString(R.string.share_text), R.drawable.ic_send).apply {
            tag = "view_share"
            setOnClickListener { share() }
        }
        val half = { LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad() / 4 } }
        actions.addView(saveButton, half())
        actions.addView(shareButton, half())
        column.addView(actions)
        // Where the save went, next to the button that made it, with a way to see it there.
        saved = Ui.text(this, "", palette.good, bold = true).apply {
            tag = "view_saved"
            setPadding(0, pad() / 2, 0, 0)
            visibility = View.GONE
        }
        column.addView(saved)
        seeButton = Ui.button(this, palette, getString(R.string.picture_see_photos), R.drawable.ic_picture).apply {
            tag = "view_see"
            visibility = View.GONE
            setOnClickListener { savedUri?.let { if (!PictureExport.view(this@PictureViewActivity, it)) core.note(UiText(R.string.picture_no_gallery)) } }
        }
        column.addView(seeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Which version the chat shows, and the choice to change it: only on this phone, nothing goes by radio.
        inChat = Ui.text(this, "", palette.text, bold = true).apply {
            tag = "view_in_chat"
            setPadding(0, pad(), 0, pad() / 2)
        }
        column.addView(inChat)
        useButton = Ui.button(this, palette, getString(R.string.picture_use_chat), R.drawable.ic_check, filled = true).apply {
            tag = "view_use"
            setOnClickListener { useInChat() }
        }
        column.addView(useButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = pad() / 4
        })
        originalButton = Ui.button(this, palette, getString(R.string.picture_use_original), R.drawable.ic_back).apply {
            tag = "view_original"
            setOnClickListener {
                core.useOriginal(messageId)
                showChatChoice()
            }
        }
        column.addView(originalButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        show(ScrollView(this).apply { isFillViewport = true; addView(column) })
        choose(look)
    }

    private fun renderLooks() {
        looks.removeAllViews()
        PictureLook.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, pad() / 4) }
            pair.forEach { choice ->
                val usable = PictureLooks.available(this, choice)
                row.addView(Ui.button(this, palette, getString(choice.label), null, filled = choice == look).apply {
                    tag = "look:${choice.key}"
                    isSelected = choice == look
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    Ui.enable(this, usable)
                    setOnClickListener { choose(choice) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad() / 4 })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            looks.addView(row)
        }
    }

    private fun choose(next: PictureLook) {
        look = next
        renderLooks()
        aiNote.visibility = if (next.ai) View.VISIBLE else View.GONE
        showChatChoice()
        val ready = made[next]
        if (ready != null) display(ready) else make(next)
    }

    /** Works off the main thread; the large AI models take seconds to minutes on a slow phone, so progress shows. */
    private fun make(next: PictureLook) {
        val from = source ?: return
        val mine = ++job
        val previous = running
        previous?.cancelled = true
        val request = PictureLooks.Job()
        running = request
        enableActions(false)
        // Until the view before it has stopped (at most one piece of the picture), say so rather than sit at 0 %.
        if (previous != null) showWaiting() else showProgress(0)
        val context: Context = applicationContext
        Thread {
            val result = runCatching {
                // An AI view made before for this picture opens at once; a new one is kept for next time.
                PictureLooks.cached(context, messageId, next) ?: PictureLooks.make(context, next, from, request, started = {
                    runOnUiThread { if (mine == job && !isDestroyed) showProgress(0) }
                }, preparing = { percent ->
                    runOnUiThread { if (mine == job && !isDestroyed) showPreparing(percent) }
                }) { percent ->
                    runOnUiThread { if (mine == job && !isDestroyed) showProgress(percent) }
                }.also { PictureLooks.keep(context, messageId, next, it) }
            }.onFailure { if (it !is PictureLooks.Cancelled) Log.w(TAG, "view ${next.key} not made", it) }
            runOnUiThread {
                if (running === request) running = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.getOrNull()?.let { made[next] = it }
                if (mine != job || result.exceptionOrNull() is PictureLooks.Cancelled) return@runOnUiThread
                progress.visibility = View.GONE
                val done = result.getOrNull()
                if (done != null) display(done) else info.text = getString(R.string.look_failed)
            }
        }.start()
    }

    /** The first use of a model on this phone tries each engine once; it can take a while, so it says so. */
    private fun showPreparing(percent: Int) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = percent
        info.text = getString(R.string.look_preparing, percent)
    }

    private fun showWaiting() {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        info.text = getString(R.string.look_waiting)
    }

    private fun showProgress(percent: Int) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = percent <= 0 || percent >= 100
        if (!progress.isIndeterminate) progress.progress = percent
        info.text = getString(R.string.look_working, percent)
    }

    private fun display(done: PictureLooks.Made) {
        progress.visibility = View.GONE
        image.setImageDrawable(BitmapDrawable(resources, done.bitmap).apply { isFilterBitmap = look != PictureLook.EXACT })
        val seconds = "%.1f".format(resources.configuration.locales[0], done.millis / 1000.0)
        info.text = getString(R.string.look_time, done.bitmap.width, done.bitmap.height, seconds)
        enableActions(true)
    }

    /** "In the chat: …", Use offered when this view is not what the chat shows, Back to the original when a view is. */
    private fun showChatChoice() {
        val current = chosen()
        inChat.text = getString(R.string.picture_in_chat, if (current == null) getString(R.string.picture_as_came) else getString(current.label))
        useButton.visibility = if (look != (current ?: PictureLook.SMOOTH) && made[look] != null) View.VISIBLE else View.GONE
        originalButton.visibility = if (current != null) View.VISIBLE else View.GONE
    }

    private fun useInChat() {
        val bitmap = shown() ?: return
        if (look == PictureLook.SMOOTH) core.useOriginal(messageId)
        else if (!core.useView(messageId, bitmap, look.key)) core.note(UiText(R.string.look_failed))
        showChatChoice()
    }

    private fun enableActions(on: Boolean) {
        Ui.enable(saveButton, on)
        Ui.enable(shareButton, on)
        showChatChoice()
    }

    private fun shown(): Bitmap? = made[look]?.bitmap

    /** Into the phone's Photos; the chat keeps its own version (see [useInChat]). */
    private fun save() {
        val bitmap = shown() ?: return
        val uri = PictureExport.save(this, bitmap)
        savedUri = uri
        saved.visibility = View.VISIBLE
        if (uri == null) {
            saved.setTextColor(palette.bad)
            saved.text = getString(R.string.picture_save_failed)
            seeButton.visibility = View.GONE
            return
        }
        saved.setTextColor(palette.good)
        saved.text = getString(R.string.picture_saved)
        seeButton.visibility = View.VISIBLE
    }

    private fun share() {
        val bitmap = shown() ?: return
        if (!PictureExport.share(this, bitmap)) core.note(UiText(R.string.picture_save_failed))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LOOK, look.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        // A large model holds hundreds of MB while open; it is let go with the screen.
        // Leaving stops the view being made (at the end of its current piece), then lets the model go.
        if (isFinishing) {
            running?.cancelled = true
            Thread { PictureLooks.release() }.start()
        }
        super.onDestroy()
    }

    override fun render() = Unit

    companion object {
        const val EXTRA_MESSAGE = "message"
        private const val STATE_LOOK = "look"
        private const val TAG = "PhonemeshPicture"

        fun intent(context: Context, messageId: Long): Intent =
            Intent(context, PictureViewActivity::class.java).putExtra(EXTRA_MESSAGE, messageId)
    }
}
