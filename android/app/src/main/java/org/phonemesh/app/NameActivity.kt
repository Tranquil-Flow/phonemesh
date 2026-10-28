package org.phonemesh.app

import android.widget.SeekBar
import org.phonemesh.core.account.Avatar
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
 * The person's own name, colour and picture (accounts spec, "On the screen"; plan step 10; decisions 48-49). They say
 * their name: the voice writes it down, it shows for correcting and is read back. Then they pick a colour (eight
 * named ones, or any on the wheel) and a picture: their first letter or an emoji. A preview shows what others see.
 * Typing works too, for when no voice is downloaded yet. Saving makes the account's keys the first time.
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
    /** RGB, or -1 before one is picked. */
    private var colour = -1
    private var avatar: Avatar = Avatar.Letter
    /** A photo chosen on this screen, not saved yet. */
    private var photo: AppCore.AvatarDraft? = null
    private lateinit var preview: FrameLayout
    private lateinit var wheelBox: LinearLayout
    private lateinit var wheel: ColourWheel
    private lateinit var lightness: SeekBar
    private val pictures = LinkedHashMap<String, LinearLayout>()
    private var pressedAt = 0L
    private var wasRecording = false
    private val tick = object : Runnable {
        override fun run() {
            ring.invalidate()
            if (core.recordState == AppCore.RecordState.RECORDING) ring.postDelayed(this, 100)
        }
    }

    /** Group mode (decision 72): the people of a new group, or the chat of a group being renamed; both null for your own name. */
    private var people: List<String>? = null
    private var groupChat: Long? = null
    private val forGroup get() = people != null || groupChat != null
    private var groupPhoto: android.graphics.Bitmap? = null
    private var recentForNew = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        people = intent.getStringArrayExtra(EXTRA_PEOPLE)?.toList()
        groupChat = intent.getLongExtra(EXTRA_GROUP_CHAT, -1).takeIf { it >= 0 }
        val group = groupChat?.let { core.groupView(it) }
        if (groupChat != null && group == null) { finish(); return }
        recentForNew = group?.recentForNew ?: false
        groupPhoto = group?.face?.photo
        val saved = if (group != null) group.name to group.face.colour else if (forGroup) null else core.account()
        colour = savedInstanceState?.getInt(STATE_COLOUR, -1)?.takeIf { it >= 0 } ?: saved?.second ?: -1
        photo = savedInstanceState?.getByteArray(STATE_PHOTO)?.let { webp ->
            android.graphics.BitmapFactory.decodeByteArray(webp, 0, webp.size)?.let { core.draftOf(it) }
        }
        avatar = savedInstanceState?.getString(STATE_EMOJI)?.let { e -> runCatching { Avatar.Emoji(e) }.getOrNull() }
            ?: photo?.avatar
            ?: if (savedInstanceState?.getBoolean(STATE_LETTER) == true) Avatar.Letter
            else if (group != null) group.face.avatar else if (forGroup) Avatar.Letter else core.ownAvatar()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(if (forGroup) R.string.group_name_title else R.string.name_title)))
        column.addView(noteLine())

        micHint = Ui.text(this, getString(sayId()), palette.text, 20f, bold = true).apply {
            tag = "name_say"
            gravity = Gravity.CENTER
        }
        column.addView(micHint, full().apply { topMargin = pad() })
        val micSize = Ui.dp(this, MIC_DP)
        mic = FrameLayout(this).apply {
            tag = "name_mic"
            isClickable = true
            background = Ui.circle(palette.text)
            contentDescription = getString(sayId())
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
            hint = getString(if (forGroup) R.string.group_name_title else R.string.name_title)
            contentDescription = getString(if (forGroup) R.string.group_name_title else R.string.name_title)
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

        val previewRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        preview = Looks.avatar(this, palette, ChatKind.PERSON, PREVIEW_DP, null) as FrameLayout
        preview.tag = "name_preview"
        previewRow.addView(preview)
        previewRow.addView(Ui.text(this, getString(R.string.name_preview), palette.text, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        column.addView(previewRow, full().apply { topMargin = pad() })

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
        column.addView(Ui.button(this, palette, getString(R.string.name_more_colours), null).apply {
            tag = "name_more_colours"
            setOnClickListener {
                wheelBox.visibility = if (wheelBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (colour >= 0) wheel.show(colour)
                lightness.progress = ((wheel.lightness - ColourWheel.MIN_LIGHTNESS) / (1 - ColourWheel.MIN_LIGHTNESS) * 100).toInt()
            }
        }, full().apply { topMargin = pad() / 2 })
        wheelBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        wheel = ColourWheel(this, palette.outline) { picked -> colour = picked; render() }.apply {
            tag = "colour_wheel"
            contentDescription = getString(R.string.colour_wheel)
            isClickable = true
        }
        wheelBox.addView(wheel, LinearLayout.LayoutParams(Ui.dp(this, WHEEL_DP), Ui.dp(this, WHEEL_DP)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = pad() / 2
        })
        wheelBox.addView(Ui.text(this, getString(R.string.colour_lightness), palette.text), full())
        lightness = SeekBar(this).apply {
            tag = "colour_lightness"
            contentDescription = getString(R.string.colour_lightness)
            max = 100
            minimumHeight = Ui.dp(this@NameActivity, 48)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    wheel.lightness = ColourWheel.MIN_LIGHTNESS + (1 - ColourWheel.MIN_LIGHTNESS) * progress / 100f
                    colour = wheel.rgb
                    render()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        wheelBox.addView(lightness, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
        column.addView(wheelBox, full())

        column.addView(Ui.text(this, getString(R.string.name_picture), palette.text, 20f, bold = true), full().apply { topMargin = pad() * 2 })
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (key in listOf(PICTURE_LETTER, PICTURE_EMOJI, PICTURE_PHOTO)) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                tag = "picture_$key"
                minimumHeight = Ui.dp(this@NameActivity, 96)
                setOnClickListener {
                    when (key) {
                        PICTURE_LETTER -> { avatar = Avatar.Letter; render() }
                        PICTURE_EMOJI -> chooseEmoji()
                        else -> choosePhoto()
                    }
                }
            }
            pictures[key] = tile
            choices.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (key != PICTURE_LETTER) marginStart = pad() / 2
                topMargin = pad() / 2
            })
        }
        column.addView(choices, full())
        if (forGroup) column.addView(Ui.text(this, getString(R.string.group_photo_hub), palette.muted).apply { tag = "group_photo_hub" })

        save = Ui.button(this, palette, getString(if (people != null) R.string.group_create else R.string.name_save), R.drawable.ic_check, filled = true).apply {
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
        (avatar as? Avatar.Emoji)?.let { outState.putString(STATE_EMOJI, it.text) }
        photo?.takeIf { avatar is Avatar.Photo }?.let { outState.putByteArray(STATE_PHOTO, it.webp) }
        outState.putBoolean(STATE_LETTER, avatar == Avatar.Letter)
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
        setOnClickListener { this@NameActivity.colour = colour.rgb; render() }
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

    /** A photo from the phone (decision 50 D): at most one change a day, so say so before choosing. */
    private fun choosePhoto() {
        if (!core.photoChangeAllowed() && avatar !is Avatar.Photo) { core.note(UiText(R.string.photo_once_a_day)); return }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }, REQUEST_PHOTO)
    }

    private fun chooseEmoji() {
        @Suppress("DEPRECATION")
        startActivityForResult(EmojiActivity.intent(this), REQUEST_EMOJI)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PHOTO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                val draft = core.prepareAvatar(uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (draft == null) { core.note(UiText(R.string.picture_failed)); return@runOnUiThread }
                    photo = draft
                    avatar = draft.avatar
                    render()
                }
            }.start()
            return
        }
        if (requestCode != REQUEST_EMOJI || resultCode != RESULT_OK) return
        val emoji = data?.getStringExtra(EmojiActivity.EXTRA_EMOJI) ?: return
        avatar = runCatching { Avatar.Emoji(emoji) }.getOrNull() ?: return
        render()
    }

    /** One picture choice: what it would show, its name, and a thick outline when chosen. */
    private fun renderPicture(key: String, shows: Face?, label: String, chosen: Boolean) {
        val tile = pictures[key] ?: return
        tile.removeAllViews()
        tile.isSelected = chosen
        tile.contentDescription = label
        tile.background = Ui.ripple(Ui.outline(this, if (chosen) palette.accent else palette.surface, palette.outline,
            strokeDp = if (chosen) 3 else 2), Ui.pressedOf(palette.text))
        tile.addView(Looks.avatar(this, palette, ChatKind.PERSON, 48, shows))
        tile.addView(Ui.text(this, label, if (chosen) palette.onAccent else palette.text, bold = true).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    /** What the person said or typed, made valid; null when nothing usable is there. */
    private fun cleaned(): String? = Names.clean(field.text.toString()).takeIf { it.isNotEmpty() }

    private fun sayId() = if (forGroup) R.string.group_say_name else R.string.name_say

    private fun onSave() {
        val name = cleaned() ?: return
        if (!Names.valid(name) || name.toByteArray(Charsets.UTF_8).size > Names.MAX_BYTES || colour !in 0..0xFFFFFF) return
        val chosen = photo?.takeIf { avatar == it.avatar }
        people?.let { list ->
            val chat = core.createGroup(name, colour, avatar, chosen, list) ?: return core.note(UiText(R.string.group_cannot_send))
            startActivity(ConversationActivity.intent(this, chat))
            finish()
            return
        }
        groupChat?.let { chat ->
            if (core.restyleGroup(chat, name, colour, avatar, chosen, recentForNew)) finish()
            return
        }
        if (chosen != null) { if (!core.saveProfilePhoto(name, colour, chosen)) return } else core.saveProfile(name, colour, avatar)
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
        micHint.text = getString(if (voice) sayId() else R.string.name_no_voice)
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
        swatches.forEachIndexed { i, v ->
            v.findViewWithTag<View>("colour_check").visibility = if (AccountColours.all[i].rgb == colour) View.VISIBLE else View.INVISIBLE
        }
        val shown = colour.takeIf { it >= 0 } ?: AccountStore.UNKNOWN_COLOUR
        val label = name?.takeIf { !tooLong && !invalid } ?: "?"
        val picture = if (avatar is Avatar.Photo) photo?.preview ?: if (forGroup) groupPhoto else core.ownPhoto() else null
        Looks.show(preview, palette, ChatKind.PERSON, PREVIEW_DP, Face(label, shown, avatar, picture))
        renderPicture(PICTURE_LETTER, Face(label, shown, Avatar.Letter), getString(R.string.name_picture_letter), avatar == Avatar.Letter)
        // Before one is chosen, a smile shows what the choice is about.
        renderPicture(PICTURE_EMOJI, Face(label, shown, avatar as? Avatar.Emoji ?: Avatar.Emoji(HINT_EMOJI)), getString(R.string.name_picture_emoji),
            avatar is Avatar.Emoji)
        renderPicture(PICTURE_PHOTO, Face(label, shown, avatar, picture), getString(R.string.name_picture_photo), avatar is Avatar.Photo)
        Ui.enable(save, name != null && !tooLong && !invalid && colour in 0..0xFFFFFF)
    }

    companion object {
        private const val MIC_DP = 96
        private const val SWATCH_DP = 56
        private const val STATE_NAME = "name"
        private const val STATE_COLOUR = "colour"
        private const val REQUEST_EMOJI = 42
        private const val STATE_EMOJI = "emoji"
        private const val STATE_LETTER = "letter"
        private const val PICTURE_LETTER = "letter"
        private const val PICTURE_EMOJI = "emoji"
        private const val PICTURE_PHOTO = "photo"
        private const val REQUEST_PHOTO = 43
        private const val STATE_PHOTO = "photo"
        private const val PREVIEW_DP = 64
        private const val WHEEL_DP = 240
        private const val HINT_EMOJI = "🙂"
        private const val REQUEST_MIC = 41

        private const val EXTRA_PEOPLE = "people"
        private const val EXTRA_GROUP_CHAT = "group_chat"

        fun open(context: Context) = context.startActivity(Intent(context, NameActivity::class.java))

        /** "Name the group" for a new group with [people] (account ids), then Create group. */
        fun openForGroup(context: Context, people: List<String>) =
            context.startActivity(Intent(context, NameActivity::class.java).putExtra(EXTRA_PEOPLE, people.toTypedArray()))

        /** The group's name and picture, from Group details. */
        fun openForGroupChat(context: Context, chatId: Long) =
            context.startActivity(Intent(context, NameActivity::class.java).putExtra(EXTRA_GROUP_CHAT, chatId))
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
