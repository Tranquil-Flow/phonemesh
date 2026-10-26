package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.Readiness
import org.phonemesh.core.RecordGesture
import org.phonemesh.core.account.Names

/**
 * The person's own name and colour (accounts spec, "On the screen"; plan step 10). They say their name: the voice
 * writes it down, it shows for correcting and is read back. Then they pick a colour. Typing works too, for when no
 * voice is downloaded yet. Saving makes the account's keys the first time.
 */
class NameActivity : ScreenActivity() {
    private lateinit var field: EditText
    private lateinit var mic: FrameLayout
    private lateinit var micIcon: View
    private lateinit var ring: RecordRing
    private lateinit var micHint: TextView
    private lateinit var problem: TextView
    private lateinit var save: View
    private lateinit var listen: View
    private val swatches = ArrayList<View>()
    private var colour = -1
    private var pressedAt = 0L
    private var wasRecording = false
    private val tick = object : Runnable {
        override fun run() {
            ring.invalidate()
            if (core.recordState == AppCore.RecordState.RECORDING) ring.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = core.account()
        colour = savedInstanceState?.getInt(STATE_COLOUR, -1)?.takeIf { it >= 0 } ?: saved?.second?.let { AccountColours.indexOf(it) } ?: -1
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.name_title)))
        column.addView(noteLine())

        micHint = Ui.text(this, getString(R.string.name_say), palette.text, 20f, bold = true).apply {
            tag = "name_say"
            gravity = Gravity.CENTER
        }
        column.addView(micHint, full().apply { topMargin = pad() })
        val micSize = Ui.dp(this, MIC_DP)
        mic = FrameLayout(this).apply {
            tag = "name_mic"
            isClickable = true
            background = Ui.circle(palette.text)
            contentDescription = getString(R.string.name_say)
        }
        ring = RecordRing(this, palette.bad)
        mic.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        micIcon = Ui.icon(this, R.drawable.ic_mic, palette.background, 40)
        mic.addView(micIcon, FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER))
        mic.setOnTouchListener { _, event -> onMicTouch(event) }
        column.addView(mic, LinearLayout.LayoutParams(micSize, micSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = pad()
        })

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        field = EditText(this).apply {
            tag = "name_field"
            textSize = 26f
            setTextColor(palette.text)
            hint = getString(R.string.name_title)
            contentDescription = getString(R.string.name_title)
            // No suggestions: keyboards otherwise "correct" names that are not dictionary words (Esi became ESO).
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
            setSingleLine()
            setText(savedInstanceState?.getString(STATE_NAME) ?: saved?.first.orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        nameRow.addView(field, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listen = Ui.iconButton(this, palette, R.drawable.ic_speaker, getString(R.string.listen)).apply {
            tag = "name_listen"
            setOnClickListener { cleaned()?.let { core.speakLine(it) } }
        }
        nameRow.addView(listen)
        column.addView(nameRow, full().apply { topMargin = pad() })
        problem = Ui.text(this, "", palette.bad, bold = true).apply { tag = "name_problem" }
        column.addView(problem)
        column.addView(Ui.text(this, getString(R.string.name_check), palette.muted).apply { tag = "name_check" })

        column.addView(Ui.text(this, getString(R.string.name_colour), palette.text, 20f, bold = true), full().apply { topMargin = pad() * 2 })
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        AccountColours.all.chunked(4).forEachIndexed { rowIndex, colours ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            colours.forEachIndexed { i, c ->
                val index = rowIndex * 4 + i
                row.addView(swatch(index, c), LinearLayout.LayoutParams(0, Ui.dp(this, SWATCH_DP), 1f).apply {
                    marginEnd = if (i < 3) pad() / 2 else 0
                    topMargin = pad() / 2
                })
            }
            grid.addView(row, full())
        }
        column.addView(grid)

        save = Ui.button(this, palette, getString(R.string.name_save), R.drawable.ic_check, filled = true).apply {
            tag = "name_save"
            setOnClickListener { onSave() }
        }
        column.addView(save, full().apply { topMargin = pad() * 2 })
        show(ScrollView(this).apply { isFillViewport = true; addView(column) })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAME, field.text.toString())
        outState.putInt(STATE_COLOUR, colour)
    }

    override fun onStop() {
        if (core.recordingChatId == AppCore.NAME_DRAFT) core.cancelRecording()
        super.onStop()
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun swatch(index: Int, colour: AccountColours.Colour): View = FrameLayout(this).apply {
        tag = "colour_$index"
        isClickable = true
        contentDescription = getString(colour.name)
        setOnClickListener { this@NameActivity.colour = index; render() }
        addView(View(this@NameActivity).apply { background = Ui.circle(colour.argb) },
            FrameLayout.LayoutParams(Ui.dp(this@NameActivity, SWATCH_DP), Ui.dp(this@NameActivity, SWATCH_DP), Gravity.CENTER))
        addView(Ui.icon(this@NameActivity, R.drawable.ic_check, colour.on, 36).apply { tag = "colour_check" },
            FrameLayout.LayoutParams(Ui.dp(this@NameActivity, 36), Ui.dp(this@NameActivity, 36), Gravity.CENTER))
    }.also { swatches += it }

    /** Held: records until let go. A quick tap: records until the next tap (the chat's microphone works the same). */
    private fun onMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedAt = SystemClock.elapsedRealtime()
                wasRecording = core.recordingChatId == AppCore.NAME_DRAFT && core.recordState == AppCore.RecordState.RECORDING
                if (!wasRecording) {
                    if (!core.access.microphoneAllowed()) {
                        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                        return true
                    }
                    if (!core.startRecording(AppCore.NAME_DRAFT)) render()
                }
            }
            MotionEvent.ACTION_UP -> {
                val held = SystemClock.elapsedRealtime() - pressedAt
                if (wasRecording || held >= RecordGesture.TAP_MS) core.finishRecording()
                mic.performClick()
            }
            MotionEvent.ACTION_CANCEL -> core.finishRecording()
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            core.note(UiText(R.string.mic_denied))
        }
    }

    /** What the person said or typed, made valid; null when nothing usable is there. */
    private fun cleaned(): String? = Names.clean(field.text.toString()).takeIf { it.isNotEmpty() }

    private fun onSave() {
        val name = cleaned() ?: return
        if (!Names.valid(name) || name.toByteArray(Charsets.UTF_8).size > Names.MAX_BYTES || colour !in AccountColours.all.indices) return
        core.saveAccount(name, AccountColours.all[colour].rgb)
        finish()
    }

    override fun render() {
        if (!::save.isInitialized) return
        // A recording just finished: what was heard becomes the name, is shown, and is read back.
        val heard = core.draft(AppCore.NAME_DRAFT)
        if (core.recordState == AppCore.RecordState.IDLE && heard != null) {
            core.setDraft(AppCore.NAME_DRAFT, null)
            val name = Names.fit(Names.clean(heard))
            if (name.isNotEmpty()) {
                field.setText(name)
                field.setSelection(field.text.length)
                core.speakLine(name)
            }
        }
        val voice = core.readiness() is Readiness.Ready
        val recording = core.recordingChatId == AppCore.NAME_DRAFT && core.recordState == AppCore.RecordState.RECORDING
        val writing = core.recordingChatId == AppCore.NAME_DRAFT && core.recordState == AppCore.RecordState.TRANSCRIBING
        Ui.enable(mic, voice && !writing)
        micHint.text = getString(if (voice) R.string.name_say else R.string.name_no_voice)
        ring.startedAt = if (recording) core.recordingStartedAt else 0L
        ring.removeCallbacks(tick)
        if (recording) ring.post(tick)
        micIcon.visibility = if (writing) View.INVISIBLE else View.VISIBLE
        val name = cleaned()
        val tooLong = name != null && name.toByteArray(Charsets.UTF_8).size > Names.MAX_BYTES
        val invalid = name != null && !tooLong && !Names.valid(name)
        problem.text = when {
            tooLong -> getString(R.string.name_too_long)
            invalid -> getString(R.string.name_invalid)
            else -> ""
        }
        problem.visibility = if (problem.text.isEmpty()) View.GONE else View.VISIBLE
        Ui.enable(listen, name != null && voice)
        swatches.forEachIndexed { i, v -> v.findViewWithTag<View>("colour_check").visibility = if (i == colour) View.VISIBLE else View.INVISIBLE }
        Ui.enable(save, name != null && !tooLong && !invalid && colour in AccountColours.all.indices)
    }

    companion object {
        private const val MIC_DP = 96
        private const val SWATCH_DP = 56
        private const val STATE_NAME = "name"
        private const val STATE_COLOUR = "colour"
        private const val REQUEST_MIC = 41

        fun open(context: Context) = context.startActivity(Intent(context, NameActivity::class.java))
    }
}

/** Suggested colours, each with a spoken name for TalkBack; the card carries the colour itself (RGB, decision 48). */
object AccountColours {
    class Colour(val argb: Int, val on: Int, val name: Int) { val rgb: Int get() = argb and 0xFFFFFF }

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF111111.toInt()

    val all = listOf(
        Colour(0xFFE53935.toInt(), WHITE, R.string.colour_red),
        Colour(0xFFFB8C00.toInt(), INK, R.string.colour_orange),
        Colour(0xFFFDD835.toInt(), INK, R.string.colour_yellow),
        Colour(0xFF43A047.toInt(), WHITE, R.string.colour_green),
        Colour(0xFF1E88E5.toInt(), WHITE, R.string.colour_blue),
        Colour(0xFF8E24AA.toInt(), WHITE, R.string.colour_purple),
        Colour(0xFFEC407A.toInt(), INK, R.string.colour_pink),
        Colour(0xFF6D4C41.toInt(), WHITE, R.string.colour_brown),
    )

    /** A saved RGB colour, opaque. */
    fun argb(rgb: Int): Int = (rgb and 0xFFFFFF) or 0xFF000000.toInt()

    /** The suggestion that is [rgb], or -1. */
    fun indexOf(rgb: Int): Int = all.indexOfFirst { it.rgb == rgb }
}
