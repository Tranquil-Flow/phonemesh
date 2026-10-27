package org.phonemesh.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.phonemesh.core.backup.RecoveryKey

/**
 * Recovery kit (account recovery spec 5.3-5.4, RC1-RC6 A; backup purpose 0). Make a kit: a card with a QR code and
 * twelve words, and a file saved somewhere else; then try the saved file with the card, which marks it checked.
 * Restore: open a saved file with its card and keep the people, to add again on this phone. The key exists only while
 * its card is shown and is never stored (RC6: keeping it is off).
 */
class RecoveryActivity : ScreenActivity() {
    private enum class Step { START, CARD, SAVED, CHECK, RESTORE, FOUND }

    private lateinit var body: LinearLayout
    private var step = Step.START
    private var key: RecoveryKey? = null
    private var file: ByteArray? = null
    private var found: Recovery.Opened.Ok? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.recovery_title)))
        column.addView(noteLine())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "recovery_body" }
        column.addView(body)
        show(ScrollView(this).apply { addView(column) })
        build()
    }

    override fun onDestroy() {
        key = null
        file = null
        super.onDestroy()
    }

    private fun line(id: Int, vararg args: Any, bold: Boolean = false) =
        body.addView(Ui.text(this, getString(id, *args), palette.text, bold = bold).apply { setPadding(0, pad() / 2, 0, pad() / 2) })

    private fun action(tag: String, id: Int, icon: Int?, filled: Boolean = false, onClick: () -> Unit) =
        body.addView(Ui.button(this, palette, getString(id), icon, filled = filled).apply {
            this.tag = tag
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 2 })

    /** App changes (radio, mail) do not touch this screen: rebuilding would erase words being typed. */
    override fun render() = Unit

    private fun build() {
        body.removeAllViews()
        when (step) {
            Step.START -> start()
            Step.CARD -> card()
            Step.SAVED -> {
                line(R.string.recovery_try, bold = true)
                action("recovery_try", R.string.recovery_try_button, R.drawable.ic_play, filled = true) { key = null; pick(REQUEST_CHECK) }
            }
            Step.CHECK, Step.RESTORE -> words()
            Step.FOUND -> foundPeople()
        }
    }

    private fun start() {
        line(R.string.recovery_why, bold = true)
        line(R.string.recovery_pictures)
        val checked = core.settings.recoveryChecked
        if (checked > 0) line(R.string.recovery_checked, Ui.date(this, checked, "dMMMMyyyy"))
        action("recovery_make", R.string.recovery_make, R.drawable.ic_person, filled = true) {
            if (core.account() == null) { NameActivity.open(this); return@action }
            key = RecoveryKey.generate()
            step = Step.CARD
            build()
        }
        action("recovery_restore", R.string.recovery_restore, R.drawable.ic_person) { pick(REQUEST_RESTORE) }
        val saved = core.history.recoveredRows()
        if (saved.isNotEmpty()) {
            line(R.string.recovery_saved_people, bold = true)
            for (person in saved) {
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    tag = "recovered:${person.name}"
                    addView(Ui.content(Ui.text(this@RecoveryActivity, person.name, palette.text, bold = true)),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Ui.button(this@RecoveryActivity, palette, getString(R.string.recovery_add_again), R.drawable.ic_person,
                        description = getString(R.string.recovery_add_again) + " " + person.name).apply {
                        setOnClickListener { AddSomeoneActivity.open(this@RecoveryActivity) }
                    })
                })
            }
        }
    }

    private fun card() {
        val shown = key ?: run { step = Step.START; build(); return }
        line(R.string.recovery_keep_private, bold = true)
        val size = Ui.dp(this, 240)
        body.addView(ImageView(this).apply {
            tag = "recovery_qr"
            contentDescription = getString(R.string.recovery_keep_private)
            setImageBitmap(AddSomeoneActivity.qr(shown.text(), size))
        }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = pad() / 2 })
        val words = shown.words(wordList()).withIndex().joinToString("   ") { (i, w) -> "${i + 1}. $w" }
        body.addView(Ui.content(Ui.text(this, words, palette.text, bold = true)).apply { tag = "recovery_words" })
        line(R.string.recovery_elsewhere)
        action("recovery_save", R.string.recovery_save, R.drawable.ic_check, filled = true) { create() }
    }

    private fun words() {
        line(if (step == Step.CHECK) R.string.recovery_try else R.string.recovery_restore, bold = true)
        val entry = EditText(this).apply {
            tag = "recovery_entry"
            hint = getString(R.string.recovery_type)
            contentDescription = getString(R.string.recovery_type)
            textSize = Ui.BODY_SP
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
            // Private: no suggestions, no learning, never saved with the screen.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        body.addView(entry)
        action("recovery_open", R.string.recovery_open, R.drawable.ic_check, filled = true) { open(entry.text.toString()) }
    }

    private fun foundPeople() {
        val ok = found ?: run { step = Step.START; build(); return }
        line(R.string.recovery_found, ok.copy.contacts.size, bold = true)
        for (c in ok.copy.contacts) body.addView(Ui.content(Ui.text(this, c.savedName, palette.text)))
        line(R.string.recovery_new_identity)
        line(R.string.recovery_compare)
        action("recovery_keep", R.string.recovery_keep, R.drawable.ic_check, filled = true) {
            Recovery.restore(core.history, ok, System.currentTimeMillis())
            found = null
            file = null
            step = Step.START
            build()
            core.note(UiText(R.string.recovery_kept))
        }
    }

    /** The key from typed words (either list) or a pasted `pmrk1:` card text; null when nothing matches. */
    private fun keyFrom(typed: String): RecoveryKey? {
        val text = typed.trim()
        if (text.startsWith(RecoveryKey.PREFIX)) return RecoveryKey.fromText(text)
        return listOf(wordList(), otherList()).firstNotNullOfOrNull { RecoveryKey.fromWords(text, it) }
    }

    private fun open(typed: String) {
        val bytes = file ?: return
        val entered = keyFrom(typed) ?: run { core.note(UiText(R.string.recovery_bad_words)); return }
        when (val o = Recovery.open(bytes, entered)) {
            is Recovery.Opened.Failed -> core.note(UiText(problemText(o.problem)))
            is Recovery.Opened.Ok -> if (step == Step.CHECK) {
                core.settings.recoveryChecked = System.currentTimeMillis()
                file = null
                step = Step.START
                build()
                core.note(UiText(R.string.recovery_check_ok, listOf(o.copy.contacts.size)))
            } else {
                found = o
                step = Step.FOUND
                build()
            }
        }
    }

    private fun problemText(problem: Recovery.Problem) = when (problem) {
        Recovery.Problem.NOT_A_COPY -> R.string.recovery_not_copy
        Recovery.Problem.KEY_OR_FILE_DOES_NOT_MATCH -> R.string.recovery_no_match
        Recovery.Problem.NOT_CONTACTS, Recovery.Problem.TOO_NEW, Recovery.Problem.TOO_BIG -> R.string.recovery_too_new
    }

    private fun wordList() = RecoveryKey.WordList.load(if (core.currentLanguage().tag.startsWith("fr")) "french" else "english")
    private fun otherList() = RecoveryKey.WordList.load(if (core.currentLanguage().tag.startsWith("fr")) "english" else "french")

    private fun create() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME
            putExtra(Intent.EXTRA_TITLE, "phonemesh-contacts.pmbk")
        }, REQUEST_CREATE)
    }

    private fun pick(request: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, request)
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_CREATE -> save(uri)
            REQUEST_CHECK, REQUEST_RESTORE -> read(uri)?.let {
                file = it
                step = if (requestCode == REQUEST_CHECK) Step.CHECK else Step.RESTORE
                build()
            }
        }
    }

    private fun save(uri: Uri) {
        val shown = key ?: return
        val bytes = Recovery.export(core.history, shown, System.currentTimeMillis(), core.settings.nextRecoveryGeneration())
        val written = bytes != null && runCatching {
            checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        }.isSuccess
        if (!written) { core.note(UiText(R.string.recovery_file_failed)); return }
        // The card is now only on paper: the check below asks for it again (RC4 A).
        key = null
        step = Step.SAVED
        build()
    }

    private fun read(uri: Uri): ByteArray? = runCatching {
        checkNotNull(contentResolver.openInputStream(uri)).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                check(out.size() <= MAX_FILE) { "too big" }
            }
            out.toByteArray()
        }
    }.getOrNull() ?: run { core.note(UiText(R.string.recovery_read_failed)); null }

    companion object {
        const val MIME = "application/octet-stream"
        private const val REQUEST_CREATE = 41
        private const val REQUEST_CHECK = 42
        private const val REQUEST_RESTORE = 43
        private const val MAX_FILE = 16 * 1024 * 1024 + 4096

        fun open(activity: Activity) = activity.startActivity(Intent(activity, RecoveryActivity::class.java))
    }
}
