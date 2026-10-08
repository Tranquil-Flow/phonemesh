package org.phonemesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.phonemesh.core.DraftFit
import org.phonemesh.core.PeopleHeard
import org.phonemesh.core.RecordGesture
import java.util.Date

/** One chat: bubbles with ▶, the big microphone, the draft with ✕ ▶ ➤. Nothing plays or sends by itself. */
class ConversationActivity : ScreenActivity() {
    private var chatId = -1L
    private var built = false
    private var started: AppCore? = null
    private var editing = false
    private var pressing = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private val bubbles = BubbleAdapter()
    private lateinit var title: TextView
    private lateinit var people: TextView
    private lateinit var radioStatus: ImageView
    private lateinit var draftBox: LinearLayout
    private lateinit var draftText: TextView
    private lateinit var draftEdit: EditText
    private lateinit var tooLong: TextView
    private lateinit var micBar: LinearLayout
    private lateinit var mic: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var micLabel: TextView
    private lateinit var ring: RecordRing
    private lateinit var cancelHint: TextView
    private lateinit var lockHint: ImageView
    private lateinit var keyboard: ImageButton
    private lateinit var draftBar: LinearLayout
    private lateinit var playDraft: ImageButton
    private lateinit var send: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        val chat = core.history.chat(chatId)
        if (chat == null) { finish(); return }
        editing = savedInstanceState?.getBoolean(STATE_EDITING) ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar(chat))
        root.addView(noteLine())
        val list = ListView(this).apply {
            tag = "bubbles"
            adapter = bubbles
            divider = null
            isStackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(draftArea())
        root.addView(micBarView())
        root.addView(draftBarView())
        show(root)
        built = true
        if (editing) draftEdit.setText(core.draft(chatId).orEmpty())
    }

    private fun topBar(chat: Chat): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@ConversationActivity, 64)
            setPadding(pad() / 4, 0, pad() / 2, 0)
        }
        bar.addView(backButton())
        bar.addView(Looks.avatar(this, palette, chat.kind, 40).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = pad() / 4
        })
        title = Ui.content(Ui.text(this, "", palette.text, 20f, bold = true).apply {
            tag = "title"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        people = Looks.peopleBadge(this, palette).apply { tag = "people" }
        bar.addView(people)
        radioStatus = ImageView(this).apply {
            tag = "radio_status"
            scaleType = ImageView.ScaleType.CENTER
        }
        bar.addView(radioStatus, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        return bar
    }

    private fun draftArea(): View {
        draftBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "draft"
            setPadding(pad(), pad() / 2, pad(), pad() / 2)
        }
        draftText = Ui.content(Ui.text(this, "", palette.text).apply {
            tag = "draft_text"
            isClickable = true
            minHeight = Ui.dp(this@ConversationActivity, 48)
            setPadding(pad(), pad(), pad(), pad())
            setOnClickListener { startEditing() }
        })
        draftEdit = EditText(this).apply {
            tag = "draft_edit"
            textSize = Ui.BODY_SP
            setTextColor(palette.text)
            hint = getString(R.string.draft_hint)
            contentDescription = getString(R.string.draft_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            setHorizontallyScrolling(false)
            maxLines = 5
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!editing) return
                    core.setDraft(chatId, s?.toString()?.replace('\n', ' '))
                    renderDraftState()
                }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { stopEditing(); true } else false
            }
        }
        tooLong = Ui.text(this, getString(R.string.too_long), palette.bad, bold = true).apply {
            tag = "too_long"
            visibility = View.GONE
        }
        draftBox.addView(draftText)
        draftBox.addView(draftEdit)
        draftBox.addView(tooLong)
        return draftBox
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun micBarView(): View {
        micBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad(), pad() / 2, pad(), pad())
        }
        cancelHint = Ui.text(this, getString(R.string.slide_to_cancel), palette.bad, bold = true).apply {
            tag = "cancel_hint"
            visibility = View.INVISIBLE
        }
        micBar.addView(cancelHint, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        lockHint = Ui.icon(this, R.drawable.ic_lock, palette.text, 32).apply {
            tag = "lock_hint"
            visibility = View.INVISIBLE
        }
        column.addView(lockHint)
        val micSize = Ui.dp(this, MIC_DP)
        mic = FrameLayout(this).apply {
            tag = "mic"
            isClickable = true
            background = Ui.circle(palette.text)
        }
        ring = RecordRing(this, palette.bad)
        mic.addView(ring, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        micIcon = Ui.icon(this, R.drawable.ic_mic, palette.background, 40)
        mic.addView(micIcon, FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER))
        mic.setOnTouchListener { _, event -> onMicTouch(event) }
        mic.setOnClickListener { onMicClick() }
        column.addView(mic, LinearLayout.LayoutParams(micSize, micSize))
        micLabel = Ui.text(this, getString(R.string.talk), palette.text, bold = true).apply { gravity = Gravity.CENTER }
        column.addView(micLabel)
        micBar.addView(column)
        val right = FrameLayout(this)
        keyboard = Ui.iconButton(this, palette, R.drawable.ic_keyboard, getString(R.string.keyboard)).apply {
            tag = "keyboard"
            setOnClickListener { startEditing() }
        }
        right.addView(keyboard, FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48), Gravity.END or Gravity.CENTER_VERTICAL))
        micBar.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return micBar
    }

    private fun draftBarView(): View {
        draftBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "draft_bar"
            setPadding(pad(), pad() / 2, pad(), pad())
        }
        val clear = Ui.button(this, palette, getString(R.string.draft_clear), R.drawable.ic_close).apply {
            tag = "draft_clear"
            setOnClickListener {
                if (core.playingId == AppCore.DRAFT_PLAYBACK) core.stopPlayback()
                core.setDraft(chatId, null)
                stopEditing()
            }
        }
        playDraft = Ui.iconButton(this, palette, R.drawable.ic_play, getString(R.string.listen), 64).apply {
            tag = "draft_play"
            setOnClickListener { core.playDraft(chatId) }
        }
        send = Ui.button(this, palette, getString(R.string.send), R.drawable.ic_send, filled = true).apply {
            tag = "draft_send"
            setOnClickListener {
                if (core.send(chatId)) stopEditing() else render()
            }
        }
        draftBar.addView(clear, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        draftBar.addView(playDraft, LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)).apply {
            marginStart = pad()
            marginEnd = pad()
        })
        draftBar.addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return draftBar
    }

    // ---- Microphone

    /** Hold / slide up / slide left. Returning false lets the tap reach onMicClick (not ready, or locked). */
    private fun onMicTouch(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!core.canTalk() || core.recordState != AppCore.RecordState.IDLE) return false
                if (!core.access.microphoneAllowed()) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                    return true
                }
                downX = event.rawX
                downY = event.rawY
                downAt = event.eventTime
                pressing = true // before startRecording: its render must not treat the press as lost
                pressing = core.startRecording(chatId)
                return pressing
            }
            MotionEvent.ACTION_MOVE -> if (pressing) {
                when (RecordGesture.onMove(event.rawX - downX, event.rawY - downY, density)) {
                    RecordGesture.Phase.CANCELLED -> { pressing = false; core.cancelRecording() }
                    RecordGesture.Phase.LOCKED -> { pressing = false; core.recordLocked = true; render() }
                    else -> cancelHint.alpha = 1f - 0.6f * RecordGesture.cancelProgress(event.rawX - downX, density)
                }
            }
            MotionEvent.ACTION_UP -> if (pressing) {
                pressing = false
                when (RecordGesture.onRelease(event.rawX - downX, event.rawY - downY, density, event.eventTime - downAt)) {
                    RecordGesture.Phase.LOCKED -> { core.recordLocked = true; render() }
                    RecordGesture.Phase.CANCELLED -> core.cancelRecording()
                    else -> core.finishRecording()
                }
            }
            // The window detaching (rotation, or leaving; onScreenStop cancels in that case) sends a cancel that is not the user's.
            MotionEvent.ACTION_CANCEL -> if (pressing && !isChangingConfigurations && !isFinishing) {
                pressing = false
                core.cancelRecording()
            }
        }
        return true
    }

    /** Taps (and TalkBack): stop a locked recording, go back to the setup cards, or start locked. */
    private fun onMicClick() {
        when {
            core.recordState == AppCore.RecordState.RECORDING -> core.finishRecording()
            core.recordState == AppCore.RecordState.TRANSCRIBING -> Unit
            !core.canTalk() -> finish()
            !core.access.microphoneAllowed() -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            core.startRecording(chatId) -> { core.recordLocked = true; render() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) core.note(UiText(R.string.mic_denied))
    }

    // ---- Draft editing

    private fun startEditing() {
        editing = true
        render()
        draftEdit.setText(core.draft(chatId).orEmpty())
        draftEdit.setSelection(draftEdit.text.length)
        draftEdit.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(draftEdit, 0)
    }

    private fun stopEditing() {
        editing = false
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(draftEdit.windowToken, 0)
        render()
    }

    // ---- Lifecycle

    override fun onScreenStart() {
        started = core
        if (built) core.openChat(chatId)
    }

    /** Releases the AppCore this screen started with: a late stop must not touch a newer one. */
    override fun onScreenStop() {
        val owner = started ?: return
        started = null
        owner.closeChat(chatId)
        if (!isChangingConfigurations) {
            owner.cancelRecording()
            owner.stopPlayback()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_EDITING, editing)
        super.onSaveInstanceState(outState)
    }

    // ---- Rendering

    override fun render() {
        if (!built) return
        val chat = core.history.chat(chatId) ?: run { finish(); return }
        title.text = core.chatTitle(chat)
        Looks.showPeople(people, if (chat.kind == ChatKind.GROUP) PeopleHeard.count(core.history.sendersHeard(chatId), null) else null)
        Looks.radio(radioStatus, palette, core.connected)
        bubbles.update(chat, core.history.messages(chatId))
        val recordingHere = core.recordState == AppCore.RecordState.RECORDING && core.recordingChatId == chatId
        // A finger on the microphone does not survive a rotation: the recording carries on, locked.
        if (recordingHere && !pressing) core.recordLocked = true
        val draft = core.draft(chatId)
        val drafting = draft != null || editing
        draftBox.visibility = if (drafting) View.VISIBLE else View.GONE
        draftText.visibility = if (draft != null && !editing) View.VISIBLE else View.GONE
        draftEdit.visibility = if (editing) View.VISIBLE else View.GONE
        draftText.text = draft.orEmpty()
        draftText.contentDescription = draft
        micBar.visibility = if (drafting) View.GONE else View.VISIBLE
        draftBar.visibility = if (drafting) View.VISIBLE else View.GONE
        renderDraftState()
        renderMic(recordingHere)
    }

    private fun renderDraftState() {
        val fit = core.draftFit(chatId)
        val tooLongNow = fit == DraftFit.TOO_LONG
        tooLong.visibility = if (tooLongNow) View.VISIBLE else View.GONE
        draftBox.background = Ui.outline(this, palette.background, if (tooLongNow) palette.bad else palette.outline,
            strokeDp = if (tooLongNow) 3 else 2, dashed = true)
        Ui.enable(send, fit == DraftFit.FITS)
        val playing = core.playingId == AppCore.DRAFT_PLAYBACK
        playDraft.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playDraft.imageTintList = ColorStateList.valueOf(if (playing) palette.good else palette.text)
        playDraft.contentDescription = getString(if (playing) R.string.stop_listening else R.string.listen)
        Ui.enable(playDraft, fit != DraftFit.EMPTY)
    }

    private fun renderMic(recordingHere: Boolean) {
        val locked = recordingHere && core.recordLocked
        mic.alpha = if (core.canTalk() || recordingHere) 1f else 0.35f
        ring.startedAt = if (recordingHere) core.recordingStartedAt else 0L
        micIcon.setImageResource(if (locked) R.drawable.ic_stop else R.drawable.ic_mic)
        val label = getString(if (locked) R.string.stop_recording else R.string.talk)
        mic.contentDescription = label
        micLabel.text = label
        cancelHint.visibility = if (recordingHere && !locked) View.VISIBLE else View.INVISIBLE
        lockHint.visibility = if (recordingHere) View.VISIBLE else View.INVISIBLE
        lockHint.contentDescription = if (locked) getString(R.string.recording_locked) else null
        lockHint.importantForAccessibility = if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        Ui.enable(keyboard, !recordingHere)
    }

    private inner class BubbleAdapter : BaseAdapter() {
        private var chat: Chat? = null
        private var items: List<Message> = emptyList()
        fun update(chat: Chat, messages: List<Message>) {
            this.chat = chat
            items = messages
            notifyDataSetChanged()
        }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = bubble(items[position], chat?.kind == ChatKind.GROUP)
    }

    private fun bubble(message: Message, group: Boolean): View {
        val mine = message.direction == Direction.OUT
        val row = LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(pad(), pad() / 4, pad(), pad() / 4)
            tag = "message:${message.id}"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad() / 2, pad() / 2)
            background = Ui.outline(this@ConversationActivity, if (mine) palette.sent else palette.surface, palette.outline)
        }
        if (group && !mine && message.senderId != null) {
            box.addView(Ui.content(Ui.text(this, core.senderName(message.senderId), palette.text, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }))
        }
        box.addView(Ui.content(Ui.text(this, message.text, palette.text).apply {
            maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
        }))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val playing = core.playingId == message.id
        line.addView(Ui.iconButton(this, palette, if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (playing) R.string.stop_listening else R.string.listen), color = if (playing) palette.good else palette.text).apply {
            tag = "play:${message.id}"
            setOnClickListener { core.play(message.id) }
        })
        line.addView(Ui.text(this, DateFormat.getTimeFormat(this).format(Date(message.time)), palette.muted).apply {
            setPadding(pad() / 2, 0, pad() / 2, 0)
        })
        message.status?.let { status ->
            val look = Looks.status(status, palette)
            line.addView(Looks.statusIcon(this, look).apply { tag = "status:${message.id}" })
            if (look.showLabel) line.addView(Ui.text(this, getString(look.label), look.color, bold = true).apply {
                tag = "status_label:${message.id}"
                setPadding(pad() / 4, 0, 0, 0)
            })
        }
        box.addView(line)
        if (message.status?.canResend == true) {
            box.addView(Ui.button(this, palette, getString(R.string.resend), R.drawable.ic_retry).apply {
                tag = "resend:${message.id}"
                setOnClickListener { core.resend(message.id) }
            })
        }
        row.addView(box)
        return row
    }

    companion object {
        const val EXTRA_CHAT_ID = "chat"
        const val MIC_DP = 80
        private const val STATE_EDITING = "editing"
        private const val REQUEST_MIC = 1

        fun intent(context: Context, chatId: Long): Intent =
            Intent(context, ConversationActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
    }
}
