package org.phonemesh.app

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.DayName
import org.phonemesh.core.Days
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.DraftFit
import org.phonemesh.core.PlanDay
import org.phonemesh.core.PlanWindow
import org.phonemesh.core.RecordGesture
import org.phonemesh.core.Tip
import org.phonemesh.core.TipFacts
import org.phonemesh.core.TipPlace
import org.phonemesh.core.Tips
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** One chat: bubbles with ▶, the big microphone, the draft with ✕ ▶ ➤. Nothing plays or sends by itself. */
class ConversationActivity : ScreenActivity() {
    private var chatId = -1L
    private var built = false
    private var started: AppCore? = null
    private var editing = false
    private var pressing = false
    /** The touch now on the microphone began as a hold; one that began as a tap is left to onMicClick, to its end. */
    private var holding = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    /** The finger stayed past a tap ([RecordGesture.TAP_MS]): a quick tap locks at once and is taught no gesture. */
    private var holdConfirmed = false
    private val confirmHold = Runnable {
        if (pressing && started != null) {
            holdConfirmed = true
            render()
        }
    }
    private val bubbles = BubbleAdapter()
    private lateinit var title: TextView
    private lateinit var people: TextView
    private lateinit var radioStatus: ImageView
    private lateinit var draftBox: LinearLayout
    private lateinit var replyBar: LinearLayout
    private lateinit var replyQuote: TextView
    private lateinit var plannedBox: LinearLayout
    private lateinit var later: View
    private lateinit var draftText: TextView
    private lateinit var draftEdit: EditText
    private lateinit var tooLong: TextView
    private lateinit var cutNotice: View
    private lateinit var cutPanel: HowToPanel
    private lateinit var micBar: LinearLayout
    private lateinit var mic: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var micLabel: TextView
    private lateinit var ring: RecordRing
    private lateinit var writingDots: WritingDots
    private var wasWriting = false
    private lateinit var cancelHint: TextView
    private lateinit var lockHint: ImageView
    private lateinit var micHint: View
    private lateinit var keyboard: ImageButton
    private lateinit var draftBar: LinearLayout
    private lateinit var playDraft: ImageButton
    private lateinit var playFill: GradientDrawable
    private lateinit var send: Button
    private lateinit var tipBox: FrameLayout
    private lateinit var regionScroll: ScrollView
    private lateinit var regionSlot: FrameLayout
    /** One region picture for the screen, so renders never restart it. */
    private val regionPicture by lazy { RegionPicture(this) }
    private var tip: Tip? = null
    /** The moment that has had its tip: closing or using it brings no other one until the moment changes. */
    private var tipped: TipPlace? = null

    /** Typing and pasting stop at one radio part; deleting is always allowed. */
    private val fitFilter = InputFilter { source, start, end, dest, dstart, dend ->
        if (end == start) return@InputFilter null
        // Input connections can recommit existing text while the editor is
        // recreated. An identical replacement cannot increase the packet size;
        // filtering it would silently shorten an already-oversized saved draft.
        if (source.subSequence(start, end).contentEquals(dest.subSequence(dstart, dend))) {
            return@InputFilter null
        }
        val before = dest.subSequence(0, dstart).toString()
        val after = dest.subSequence(dend, dest.length).toString()
        fun fits(count: Int): Boolean {
            // An invalid half-surrogate is not evidence that a longer valid prefix fits.
            val boundary = if (count > 0 && Character.isHighSurrogate(source[start + count - 1])) count - 1 else count
            return core.fitFor(
                chatId, (before + source.subSequence(start, start + boundary) + after).replace('\n', ' '),
            ) != DraftFit.TOO_LONG
        }
        if (fits(end - start)) return@InputFilter null
        var low = 0
        var high = end - start
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(mid)) low = mid else high = mid - 1
        }
        if (low > 0 && Character.isHighSurrogate(source[start + low - 1])) low--
        source.subSequence(start, start + low)
    }

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
        root.addView(regionArea())
        tipBox = FrameLayout(this).apply {
            visibility = View.GONE
            setPadding(pad(), pad() / 2, pad(), 0)
        }
        val bars = listOf(micBarView(), draftBarView())
        plannedBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "planned"
            setPadding(pad(), 0, pad() / 2, 0)
        }
        root.addView(fitted(plannedBox, replyBarView(), draftArea(), tipBox))
        bars.forEach { root.addView(it) }
        show(root)
        built = true
        if (chat.kind == ChatKind.DIRECT) core.note(UiText(R.string.old_chat_read_only))
        if (editing) loadDraftForEditing()
    }

    private fun topBar(chat: Chat): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@ConversationActivity, 64)
            setPadding(pad() / 4, 0, pad() / 2, 0)
        }
        bar.addView(backButton())
        bar.addView(Looks.avatar(this, palette, chat.kind, 40, core.chatColour(chat)).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = pad() / 4
        })
        title = Ui.content(Ui.text(this, "", palette.text, 20f, bold = true).apply {
            tag = "title"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            // A narrow phone gets smaller letters before any are cut; the bar's own height lets them grow back.
            setAutoSizeTextTypeUniformWithConfiguration(Ui.BODY_SP.toInt(), 20, 1, TypedValue.COMPLEX_UNIT_SP)
            if (chat.kind == ChatKind.PERSON) {
                contentDescription = getString(R.string.contact_details)
                setOnClickListener { startActivity(ContactActivity.intent(this@ConversationActivity, chat.id)) }
            }
        })
        bar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = pad() })
        people = Looks.peopleBadge(this, palette).apply { tag = "people" }
        bar.addView(people)
        radioStatus = ImageView(this).apply {
            tag = "radio_status"
            scaleType = ImageView.ScaleType.CENTER
        }
        bar.addView(radioStatus, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        return bar
    }

    /** The region card's place above the draft. It takes half the screen at most and scrolls, so Send stays in reach. */
    private fun regionArea(): View {
        regionSlot = FrameLayout(this).apply { setPadding(pad(), pad() / 2, pad(), 0) }
        regionScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) heightMeasureSpec
                else View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(heightMeasureSpec) / 2, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, height)
            }
        }.apply {
            visibility = View.GONE
            addView(regionSlot)
        }
        return regionScroll
    }

    /** Tall enough for a picture as wide as the screen; a low window (a phone on its side) keeps the small one beside the words. */
    private val roomy get() = resources.configuration.screenHeightDp >= ROOMY_DP

    /**
     * The draft and the tip, kept out of the way of what must stay in reach: the bar below them (microphone, or
     * Clear, Listen and Send) and, in a tall window, a strip of messages above. With less room than they need,
     * their pictures get smaller.
     */
    private fun fitted(vararg views: View): View = object : LinearLayout(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var height = heightMeasureSpec
            if (View.MeasureSpec.getMode(height) != View.MeasureSpec.UNSPECIFIED) {
                val bar = if (micBar.visibility == View.VISIBLE) micBar else draftBar
                bar.measure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val kept = bar.measuredHeight + if (roomy) Ui.dp(context, MESSAGES_KEPT_DP) else 0
                height = View.MeasureSpec.makeMeasureSpec((View.MeasureSpec.getSize(height) - kept).coerceAtLeast(0), View.MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, height)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    /** Above the draft while a reply is being made: the words answered, and a cross to stop replying. */
    private fun replyBarView(): View {
        replyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "reply_bar"
            visibility = View.GONE
            setPadding(pad(), pad() / 2, pad() / 2, 0)
        }
        replyBar.addView(Ui.icon(this, R.drawable.ic_reply, palette.muted, 28))
        replyQuote = Ui.content(Ui.text(this, "", palette.muted).apply {
            tag = "reply_quote"
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(pad() / 2, 0, pad() / 2, 0)
        })
        replyBar.addView(replyQuote, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        replyBar.addView(Ui.iconButton(this, palette, R.drawable.ic_close, getString(R.string.reply_cancel)).apply {
            tag = "reply_cancel"
            setOnClickListener { core.setReplyTarget(chatId, null) }
        })
        return replyBar
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
            filters = arrayOf(fitFilter)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // Loading or restoring unchanged text is not a user edit.
                    if (!editing || s?.toString().orEmpty() == core.draft(chatId).orEmpty()) return
                    core.setDraft(chatId, s?.toString()?.replace('\n', ' '))
                    render()
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
        later = Ui.button(this, palette, getString(R.string.later), R.drawable.ic_clock).apply {
            tag = "draft_later"
            setOnClickListener { askWhenToSend() }
        }
        draftBox.addView(later, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.END; topMargin = pad() / 2 })
        cutPanel = howTo("toolong", compact = !roomy) { getString(R.string.draft_cut) }
        cutNotice = FrameLayout(this).apply {
            tag = "draft_cut"
            visibility = View.GONE
            addView(cutPanel)
        }
        // Weighted: the notice's picture gives way before the draft's own words do.
        draftBox.addView(cutNotice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
        micHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = "mic_hint"
            visibility = View.GONE
            addView(Ui.text(this@ConversationActivity, getString(R.string.mic_hint), palette.onAccent, bold = true).apply {
                background = Ui.outline(this@ConversationActivity, palette.accent, palette.outline)
                setPadding(pad(), pad() / 2, pad(), pad() / 2)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(Ui.icon(this@ConversationActivity, R.drawable.ic_hint_tail, palette.accent, 16).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.dp(this@ConversationActivity, 16), Ui.dp(this@ConversationActivity, 8))
            })
        }
        // Both nested hint layouts wrap their content; MATCH_PARENT otherwise ties
        // the sentence to the microphone's width after its height was measured.
        column.addView(micHint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        writingDots = WritingDots(this, palette.background).apply {
            tag = "writing"
            visibility = View.GONE
        }
        mic.addView(writingDots, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mic.setOnTouchListener { _, event -> onMicTouch(event) }
        mic.setOnClickListener { onMicClick() }
        column.addView(mic, LinearLayout.LayoutParams(micSize, micSize))
        micLabel = Ui.text(this, getString(R.string.talk), palette.text, bold = true).apply { gravity = Gravity.CENTER }
        // Wrapping its own words: a sentence longer than the microphone is wide stays on one line.
        column.addView(micLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        micBar.addView(column)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        // Pictures: private chats only, and only in builds that carry them (BuildConfig.PICTURES).
        if (core.canSendPictures(chatId)) {
            right.addView(Ui.iconButton(this, palette, R.drawable.ic_picture, getString(R.string.picture_button)).apply {
                tag = "picture"
                setOnClickListener { startActivity(PictureActivity.intent(this@ConversationActivity, chatId)) }
            }, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        }
        keyboard = Ui.iconButton(this, palette, R.drawable.ic_keyboard, getString(R.string.keyboard)).apply {
            tag = "keyboard"
            setOnClickListener { startEditing() }
        }
        right.addView(keyboard, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
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
        // A filled round button like the microphone, so it is found at a glance; renderDraftState colours it.
        playFill = Ui.circle(palette.accent).apply { setStroke(Ui.dp(this@ConversationActivity, 2), palette.outline) }
        playDraft = Ui.iconButton(this, palette, R.drawable.ic_play, getString(R.string.listen), 64).apply {
            tag = "draft_play"
            background = Ui.ripple(playFill, Ui.pressedOf(palette.onAccent))
            setOnClickListener {
                if (tip == Tip.LISTEN) endTip(learned = true)
                core.playDraft(chatId)
            }
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

    /** Hold / slide up / slide left. Returning false for a whole touch lets the tap reach onMicClick (not ready, or locked). */
    private fun onMicTouch(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = core.canTalk() && core.recordState == AppCore.RecordState.IDLE
                if (!holding) return false
                if (!core.access.microphoneAllowed()) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                    return true
                }
                downX = event.rawX
                downY = event.rawY
                downAt = event.eventTime
                holdConfirmed = false
                window.decorView.removeCallbacks(confirmHold)
                pressing = true // before startRecording: its render must not treat the press as lost
                pressing = core.startRecording(chatId)
                if (pressing) window.decorView.postDelayed(confirmHold, RecordGesture.TAP_MS)
                return pressing
            }
            MotionEvent.ACTION_MOVE -> if (pressing) {
                when (RecordGesture.onMove(event.rawX - downX, event.rawY - downY, density)) {
                    RecordGesture.Phase.CANCELLED -> { pressing = false; cancelBySliding() }
                    RecordGesture.Phase.LOCKED -> { pressing = false; lockRecording() }
                    else -> cancelHint.alpha = 1f - 0.6f * RecordGesture.cancelProgress(event.rawX - downX, density)
                }
            }
            MotionEvent.ACTION_UP -> if (pressing) {
                pressing = false
                when (RecordGesture.onRelease(event.rawX - downX, event.rawY - downY, density, event.eventTime - downAt)) {
                    RecordGesture.Phase.LOCKED -> lockRecording()
                    RecordGesture.Phase.CANCELLED -> cancelBySliding()
                    else -> core.finishRecording()
                }
            }
            // The window detaching (rotation, or leaving; onScreenStop cancels in that case) sends a cancel that is not the user's.
            MotionEvent.ACTION_CANCEL -> if (pressing && !isChangingConfigurations && !isFinishing) {
                pressing = false
                core.cancelRecording()
            }
        }
        return holding
    }

    private fun cancelBySliding() {
        if (tip == Tip.CANCEL) endTip(learned = true)
        core.cancelRecording()
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

    private fun lockRecording() {
        core.recordLocked = true
        if (tip == Tip.LOCK) endTip(learned = true)
        // No finger, so sliding left does nothing now: the cancel tip counts one showing and the lock tip does not follow.
        if (tip == Tip.CANCEL) {
            endTip(learned = false)
            tipped = TipPlace.RECORDING
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) core.note(UiText(R.string.mic_denied))
    }

    // ---- Draft editing

    private fun startEditing() {
        editing = true
        render()
        loadDraftForEditing()
        draftEdit.setSelection(draftEdit.text.length)
        draftEdit.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(draftEdit, 0)
    }

    /** An existing oversized draft must survive opening the editor and rotation, until the user edits it. */
    private fun loadDraftForEditing() {
        draftEdit.filters = emptyArray()
        try {
            draftEdit.setText(core.draft(chatId).orEmpty())
        } finally {
            draftEdit.filters = arrayOf(fitFilter)
        }
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
        window.decorView.removeCallbacks(confirmHold)
        if (!isChangingConfigurations) endTip(learned = false, owner.settings)
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
        if (chat.kind == ChatKind.PERSON) title.contentDescription = "${core.chatTitle(chat)}: ${getString(R.string.contact_details)}"
        Looks.showPeople(people, if (chat.kind == ChatKind.GROUP) core.peopleHeard(chatId) else null)
        Looks.radio(radioStatus, palette, core.connected)
        val messages = core.history.messages(chatId)
        bubbles.update(chat, messages, core.history.reactions(chatId).groupBy { it.target })
        renderPlanned()
        // Planning needs whole words that fit, like Send; not while typing, and not while the region card asks first.
        val regionAsks = core.region.blocksSending || core.region.phase != RegionPhase.Idle
        later.visibility = if (core.draft(chatId) != null && !editing && !regionAsks && chat.kind != ChatKind.DIRECT && core.draftFit(chatId) == DraftFit.FITS) View.VISIBLE else View.GONE
        val answering = core.replyTarget(chatId)
        replyBar.visibility = if (answering != null) View.VISIBLE else View.GONE
        replyQuote.text = answering?.let { if (it.kind == MessageKind.PICTURE) getString(R.string.picture_label) else it.text }.orEmpty()
        val recordingHere = core.recordState == AppCore.RecordState.RECORDING && core.recordingChatId == chatId
        // A finger on the microphone does not survive a rotation: the recording carries on, locked.
        if (recordingHere && !pressing) core.recordLocked = true
        val draft = core.draft(chatId)
        // An old radio chat is read only: no microphone, no keyboard, no draft, no Send.
        val drafting = (draft != null || editing) && chat.kind != ChatKind.DIRECT
        draftBox.visibility = if (drafting) View.VISIBLE else View.GONE
        draftText.visibility = if (draft != null && !editing) View.VISIBLE else View.GONE
        draftEdit.visibility = if (editing) View.VISIBLE else View.GONE
        draftText.text = draft.orEmpty()
        draftText.contentDescription = draft
        micBar.visibility = if (drafting || chat.kind == ChatKind.DIRECT) View.GONE else View.VISIBLE
        draftBar.visibility = if (drafting) View.VISIBLE else View.GONE
        // A draft that cannot go out yet is explained: the radio has no region, or its region is being set.
        regionSlot.removeAllViews()
        val regionCard = if (drafting && (core.region.blocksSending || core.region.phase != RegionPhase.Idle)) {
            RegionCards.card(this, palette, core, regionPicture)
        } else {
            null
        }
        regionCard?.let { regionSlot.addView(it) }
        regionScroll.visibility = if (regionCard != null) View.VISIBLE else View.GONE
        renderDraftState()
        renderMic(recordingHere)
        if (started != null) renderTip(when {
            recordingHere -> TipPlace.RECORDING
            // One picture at a time: a region explanation or cut-off notice owns the space.
            // Otherwise the generic draft tip can push the actual discard/listen/send row off-screen.
            drafting -> if (regionCard != null || core.draftFit(chatId) == DraftFit.EMPTY || cutNotice.visibility == View.VISIBLE) null else TipPlace.DRAFT
            else -> TipPlace.CHAT
        }, messages)
    }

    /** Keep the same picture through renders; leaving its moment only hides it. */
    private fun renderTip(place: TipPlace?, messages: List<Message>) {
        tip?.let { if (it.place != place) endTip(learned = false) }
        if (tipped != place) tipped = null
        // The first-message hint owns this space while the microphone is idle.
        if (tipped == null && place != null && (place != TipPlace.CHAT || core.settings.firstMessageSent)) {
            val facts = TipFacts(
                sent = messages.any { it.direction == Direction.OUT },
                failed = messages.any { it.status?.canResend == true },
                received = messages.any { it.direction == Direction.IN },
                direct = core.history.chat(chatId)?.kind != ChatKind.GROUP,
                held = pressing && holdConfirmed && !core.recordLocked,
            )
            Tips.next(place, facts, core.settings.tipsShown, core.settings.tipShowings)?.let { showTip(it) }
        }
        (tipBox.getChildAt(0) as? HowToPanel)?.update()
        tipBox.visibility = if (tip != null) View.VISIBLE else View.GONE
    }

    private fun showTip(next: Tip) {
        tip = next
        tipped = next.place
        tipBox.removeAllViews()
        // The picture as wide as the card, like a setup step; the sentence, speaker and close sit under it.
        val card = howTo(next.key, compact = !roomy).apply {
            tag = "tip:${next.key}"
            setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
            background = Ui.outline(this@ConversationActivity, palette.surface, palette.accent, strokeDp = 3)
        }
        card.add(Ui.iconButton(this, palette, R.drawable.ic_close, getString(R.string.tip_close)).apply {
            tag = "tip_close"
            setOnClickListener { endTip(learned = true); render() }
        })
        tipBox.addView(card)
    }

    /**
     * Takes the tip away. [learned] (its close button, or its action done) it never returns; when its moment only
     * ended, or the screen was left, that counts as one showing and it is offered again (Tips.MAX_SHOWINGS).
     */
    private fun endTip(learned: Boolean, settings: Settings = core.settings) {
        val shown = tip ?: return
        if (learned) {
            settings.tipsShown = settings.tipsShown + shown.key
        } else {
            settings.tipShowings = settings.tipShowings.let { it + (shown.key to (it[shown.key] ?: 0) + 1) }
            tipped = null
        }
        tip = null
        tipBox.removeAllViews()
        tipBox.visibility = View.GONE
    }

    private fun renderDraftState() {
        val fit = core.draftFit(chatId)
        val tooLongNow = fit == DraftFit.TOO_LONG
        tooLong.visibility = if (tooLongNow) View.VISIBLE else View.GONE
        cutPanel.update()
        cutNotice.visibility = if (core.draftWasCut(chatId) && !editing) View.VISIBLE else View.GONE
        draftBox.background = Ui.outline(this, palette.background, if (tooLongNow) palette.bad else palette.outline,
            strokeDp = if (tooLongNow) 3 else 2, dashed = true)
        Ui.enable(send, fit == DraftFit.FITS && !core.region.blocksSending)
        val playing = core.playingId == AppCore.DRAFT_PLAYBACK
        playDraft.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Yellow with a dark triangle; green with the stop bars while it plays (the same in both themes).
        playFill.setColor(if (playing) palette.good else palette.accent)
        playDraft.imageTintList = ColorStateList.valueOf(if (playing) palette.background else palette.onAccent)
        playDraft.contentDescription = getString(if (playing) R.string.stop_listening else R.string.listen)
        Ui.enable(playDraft, fit != DraftFit.EMPTY)
    }

    private fun renderMic(recordingHere: Boolean) {
        // The recording has stopped and its words are not a draft yet: seconds on a real phone, so the microphone says so.
        val writing = core.recordState == AppCore.RecordState.TRANSCRIBING && core.recordingChatId == chatId
        micHint.visibility = if (!core.settings.firstMessageSent && !recordingHere && !writing) View.VISIBLE else View.GONE
        val locked = recordingHere && core.recordLocked
        mic.alpha = if (core.canTalk() || recordingHere) 1f else 0.35f
        ring.startedAt = if (recordingHere) core.recordingStartedAt else 0L
        micIcon.setImageResource(if (locked) R.drawable.ic_stop else R.drawable.ic_mic)
        micIcon.visibility = if (writing) View.INVISIBLE else View.VISIBLE
        writingDots.visibility = if (writing) View.VISIBLE else View.GONE
        val label = getString(if (locked) R.string.stop_recording else if (writing) R.string.writing_words else R.string.talk)
        mic.contentDescription = label
        micLabel.text = label
        // Said once, after the microphone has closed; nothing announces Stop into a recording.
        if (writing && !wasWriting) mic.announceForAccessibility(label)
        wasWriting = writing
        cancelHint.visibility = if (recordingHere && !locked) View.VISIBLE else View.INVISIBLE
        lockHint.visibility = if (recordingHere) View.VISIBLE else View.INVISIBLE
        lockHint.contentDescription = if (locked) getString(R.string.recording_locked) else null
        lockHint.importantForAccessibility = if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        Ui.enable(keyboard, !recordingHere && !writing)
    }

    private inner class BubbleAdapter : BaseAdapter() {
        private var chat: Chat? = null
        private var items: List<Message> = emptyList()
        var reactions: Map<Long, List<Reaction>> = emptyMap()
            private set
        fun update(chat: Chat, messages: List<Message>, reactions: Map<Long, List<Reaction>>) {
            this.chat = chat
            items = messages
            this.reactions = reactions
            notifyDataSetChanged()
        }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        // ListView can ask for a stale position during the layout right after a delete (seen under load); no crash.
        override fun getItemId(position: Int) = items.getOrNull(position)?.id ?: android.widget.AdapterView.INVALID_ROW_ID
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            bubble(items[position], chat?.kind == ChatKind.GROUP, items.getOrNull(position - 1)?.time)
    }

    private fun bubble(message: Message, group: Boolean, previousTime: Long?): View {
        val mine = message.direction == Direction.OUT
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(pad(), pad() / 4, pad(), pad() / 4)
            tag = "message:${message.id}"
        }
        val zone = ZoneId.systemDefault()
        if (Days.startsDay(previousTime, message.time, zone)) {
            row.addView(Ui.text(this, dayLabel(message.time, zone), palette.muted, bold = true).apply {
                tag = "day:${message.id}"
                gravity = Gravity.CENTER
                setPadding(0, pad() / 2, 0, pad() / 2)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad() / 2, pad() / 2)
            background = Ui.outline(this@ConversationActivity, if (mine) palette.sent else palette.surface, palette.outline)
            tag = "bubble:${message.id}"
            setOnLongClickListener { showActions(message); true }
            accessibilityDelegate = deleteLabel
        }
        if (group && !mine && message.senderId != null) {
            box.addView(Ui.content(Ui.text(this, core.senderName(message.senderId), palette.text, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }))
        }
        val picture = if (message.kind == MessageKind.PICTURE) core.history.picture(message.id) else null
        val words = if (picture != null) pictureCaption(message, picture).ifEmpty { getString(R.string.picture_label) } else message.text
        // A long press deletes, so the bubble is a target and is named: who wrote it, then the words.
        box.contentDescription = listOfNotNull(
            message.senderId?.takeIf { group && !mine }?.let { core.senderName(it) }, words,
        ).joinToString(", ")
        if (message.replyTo != null) {
            val quoted = core.quoted(message)
            val quotedWords = when {
                quoted == null -> getString(R.string.quote_missing)
                quoted.kind == MessageKind.PICTURE -> getString(R.string.picture_label)
                else -> quoted.text
            }
            box.addView(Ui.content(Ui.text(this, quotedWords, palette.muted).apply {
                tag = "quote:${message.id}"
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
                setPadding(pad() / 2, pad() / 4, pad() / 2, pad() / 4)
                background = Ui.outline(this@ConversationActivity, palette.background, palette.outline)
            }))
        }
        if (picture != null) {
            box.addView(pictureView(message, picture))
            pictureCaption(message, picture).takeIf { it.isNotEmpty() }?.let { caption ->
                val bad = picture.lost || (message.direction == Direction.OUT && message.status == DeliveryStatus.FAILED)
                box.addView(Ui.text(this, caption, if (bad) palette.bad else palette.muted, bold = bad).apply {
                    tag = "picture_caption:${message.id}"
                })
            }
            // One picture on its way: a bar fills as its pieces go or arrive (the pieces themselves are never counted aloud).
            pictureShare(message, picture)?.let { percent ->
                box.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    tag = "picture_progress:${message.id}"
                    max = 100
                    progress = percent
                    progressTintList = android.content.res.ColorStateList.valueOf(palette.good)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 8)).apply { topMargin = pad() / 4 })
            }
        } else {
            box.addView(Ui.content(Ui.text(this, message.text, palette.text).apply {
                maxWidth = (resources.displayMetrics.widthPixels * 0.7f).toInt()
            }))
        }
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val playing = core.playingId == message.id
        if (picture == null) line.addView(Ui.iconButton(this, palette, if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            getString(if (playing) R.string.stop_listening else R.string.listen), color = if (playing) palette.good else palette.text).apply {
            tag = "play:${message.id}"
            setOnClickListener {
                if (tip == Tip.ARRIVE && message.direction == Direction.IN) endTip(learned = true)
                core.play(message.id)
            }
        })
        line.addView(Ui.text(this, DateFormat.getTimeFormat(this).format(Date(message.time)), palette.muted).apply {
            setPadding(pad() / 2, 0, pad() / 2, 0)
        })
        var labelBelow: View? = null
        message.status?.let { status ->
            val look = Looks.status(status, palette)
            line.addView(Looks.statusIcon(this, look).apply { tag = "status:${message.id}" })
            if (look.showLabel) {
                val label = Ui.text(this, getString(look.label), look.color, bold = true).apply {
                    tag = "status_label:${message.id}"
                    setPadding(pad() / 4, 0, 0, 0)
                }
                // A picture bubble is only as wide as its picture: its words go on their own line, whole.
                if (picture != null) labelBelow = label else line.addView(label)
            }
        }
        box.addView(line)
        labelBelow?.let { box.addView(it) }
        message.plannedAt?.let { planned ->
            box.addView(Ui.text(this, getString(R.string.planned_for, DateFormat.getTimeFormat(this).format(Date(planned))), palette.muted).apply {
                tag = "planned_for:${message.id}"
            })
        }
        message.packetId?.let { bubbles.reactions[it] }?.takeIf { it.isNotEmpty() }?.let { list ->
            val shown = list.groupBy { it.emoji }.entries.joinToString("  ") { (emoji, all) -> if (all.size > 1) "$emoji ${all.size}" else emoji }
            box.addView(Ui.content(Ui.text(this, shown, palette.text).apply {
                tag = "reactions:${message.id}"
                contentDescription = getString(R.string.reactions, shown)
            }))
            // The user's own reaction that no radio has taken yet says so, under the marks.
            if (list.any { it.waiting }) box.addView(waitingLine("reaction_waiting:${message.id}"))
        }
        if (message.status?.canResend == true) {
            box.addView(Ui.button(this, palette, getString(R.string.resend), R.drawable.ic_retry).apply {
                tag = "resend:${message.id}"
                // Off like Send while the radio has no region or its region is being set.
                Ui.enable(this, !core.region.blocksSending)
                setOnClickListener {
                    if (tip == Tip.FAILED) endTip(learned = true)
                    core.resend(message.id)
                }
            })
        }
        // A vertical row would stretch the bubble to its width; a bubble fits its words.
        row.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row
    }

    /** A clock and "Waiting for a radio": something of the user's that goes out once a radio is ready. */
    private fun waitingLine(tag: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        this.tag = tag
        addView(ImageView(this@ConversationActivity).apply {
            setImageResource(R.drawable.ic_clock)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(this@ConversationActivity, 18), Ui.dp(this@ConversationActivity, 18)))
        addView(Ui.text(this@ConversationActivity, getString(R.string.waiting_radio), palette.muted, bold = true).apply {
            setPadding(pad() / 4, 0, 0, 0)
        })
    }

    /**
     * "Picture arriving: 2 of 4", "Sending picture: 1 of 3", "The picture did not arrive", or
     * nothing once it is whole.
     */
    private fun pictureCaption(message: Message, picture: PictureRow): String {
        val percent = pictureShare(message, picture)
        return when {
            picture.lost -> getString(R.string.picture_lost)
            // Only the other radio's silence ends a picture as failed (our own radio away makes it wait instead).
            message.direction == Direction.OUT && message.status == DeliveryStatus.FAILED -> getString(R.string.picture_no_answer)
            percent != null && message.direction == Direction.IN -> getString(R.string.picture_arriving, percent)
            percent != null -> getString(R.string.picture_sending, percent)
            else -> ""
        }
    }

    /** How much of one picture on its way has gone or arrived, 0-100; null when it is not on its way. */
    private fun pictureShare(message: Message, picture: PictureRow): Int? {
        val moving = !picture.lost && when (message.direction) {
            Direction.IN -> picture.file == null
            Direction.OUT -> message.status == DeliveryStatus.WAITING && !core.pictureWaitsForRadio(message.id)
        }
        return if (moving) (picture.have * 100 / picture.total.coerceAtLeast(1)).coerceIn(0, 100) else null
    }

    /** The picture, enlarged smoothly to the bubble's width; its blurred sketch until every piece has arrived. */
    private fun pictureView(message: Message, picture: PictureRow): View {
        val width = (resources.displayMetrics.widthPixels * 0.6f).toInt()
        val ratio = picture.height.toFloat() / picture.width.coerceAtLeast(1)
        return ImageView(this).apply {
            tag = "picture:${message.id}"
            scaleType = ImageView.ScaleType.FIT_XY
            core.pictureShown(message.id)?.let { setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, it).apply { isFilterBitmap = true }) }
            layoutParams = LinearLayout.LayoutParams(width, (width * ratio).toInt().coerceAtLeast(1))
            // A tap opens it large, with the ways to show it bigger; a long press is the message's own menu.
            contentDescription = getString(R.string.picture_open)
            setOnClickListener { startActivity(PictureViewActivity.intent(this@ConversationActivity, message.id)) }
            setOnLongClickListener { showActions(message); true }
        }
    }

    /** TalkBack names a message's long press after what it does. */
    private val deleteLabel = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, getString(R.string.message_actions)))
        }
    }

    /** "Today", "Yesterday", or the date in the app's language. */
    private fun dayLabel(time: Long, zone: ZoneId): String = when (Days.name(time, System.currentTimeMillis(), zone)) {
        DayName.TODAY -> getString(R.string.today)
        DayName.YESTERDAY -> getString(R.string.yesterday)
        DayName.DATE -> Days.date(time, zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(resources.configuration.locales[0]))
    }

    /** One dialog at a time: the long-press menu, the chat list to forward to, or the question before deleting. */
    private var dialog: AlertDialog? = null

    private fun show(next: AlertDialog) {
        dialog?.dismiss()
        next.setOnDismissListener { if (dialog === next) dialog = null }
        dialog = next
        next.show()
    }

    private fun showActions(message: Message) {
        // The long press is what the "hold" tip teaches: done once, it never shows again.
        if (tip == Tip.HOLD) endTip(learned = true) else core.settings.tipsShown = core.settings.tipsShown + Tip.HOLD.key
        tipBox.visibility = if (tip != null) View.VISIBLE else View.GONE
        val others = core.chatRows().filter { it.chat.id != chatId && !it.old }
        val picture = message.kind == MessageKind.PICTURE
        val privateOthers = others.filter { core.canSendPictures(it.chat.id) }
        // Only a message that went over the radio has an id the other phones know, so only it can be answered.
        val overRadio = message.packetId != null && core.history.chat(chatId)?.kind != ChatKind.DIRECT
        show(Actions.menu(this, palette, listOfNotNull(
            if (overRadio) Actions.Item("action_reply", R.string.reply, R.drawable.ic_reply) { core.setReplyTarget(chatId, message.id) } else null,
            if (overRadio) Actions.Item("action_react", R.string.react, R.drawable.ic_react) { askForReaction(message.id) } else null,
            if (picture) Actions.Item("action_open", R.string.picture_open, R.drawable.ic_picture) {
                startActivity(PictureViewActivity.intent(this, message.id))
            } else null,
            if (message.kind != MessageKind.TEXT) null else Actions.Item("action_copy", R.string.copy, R.drawable.ic_copy) { copy(message.text) },
            if (picture && PictureExport.canSave) Actions.Item("action_save", R.string.picture_save, R.drawable.ic_download) { savePicture(message.id) } else null,
            when {
                message.kind == MessageKind.TEXT -> Actions.Item("action_share", R.string.share_text, R.drawable.ic_send) { share(message.text) }
                picture -> Actions.Item("action_share", R.string.share_text, R.drawable.ic_send) { sharePicture(message.id) }
                else -> null
            },
            when {
                message.kind == MessageKind.TEXT && others.isNotEmpty() ->
                    Actions.Item("action_forward", R.string.forward, R.drawable.ic_forward) { askWhereToForward(message.id, others) }
                // A whole picture goes to another private chat: its screen opens with the same picture, ready to send.
                picture && core.canForwardPicture(message.id) && privateOthers.isNotEmpty() ->
                    Actions.Item("action_forward", R.string.forward, R.drawable.ic_forward) { askWhereToForward(message.id, privateOthers) }
                else -> null
            },
            Actions.Item("action_delete", R.string.delete, R.drawable.ic_delete) {
                show(Actions.confirm(this, R.string.delete_title, R.string.delete_only_here, R.string.delete) { core.deleteMessage(message.id) })
            },
        )))
    }

    /** Planned messages wait above the draft: when each goes, its words, and a cross to keep it from going. */
    private fun renderPlanned() {
        plannedBox.removeAllViews()
        val zone = ZoneId.systemDefault()
        for (planned in core.history.planned(chatId)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = "planned:${planned.id}"
            }
            row.addView(Ui.icon(this, R.drawable.ic_clock, palette.muted, 28))
            val words = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad() / 2, 0, pad() / 2, 0)
            }
            val time = DateFormat.getTimeFormat(this).format(Date(planned.due))
            val whenText = when (PlanWindow.day(planned.due, System.currentTimeMillis(), zone)) {
                PlanDay.TODAY -> getString(R.string.sends_at, time)
                PlanDay.TOMORROW -> getString(R.string.sends_tomorrow, time)
                PlanDay.OTHER -> getString(R.string.sends_on_day, dayName(planned.due), time)
            }
            words.addView(Ui.text(this, whenText, palette.text, bold = true).apply { tag = "planned_when:${planned.id}" })
            words.addView(Ui.content(Ui.text(this, planned.text, palette.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }))
            row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.iconButton(this, palette, R.drawable.ic_close, getString(R.string.planned_cancel)).apply {
                tag = "planned_cancel:${planned.id}"
                setOnClickListener { core.cancelPlanned(planned.id) }
            })
            plannedBox.addView(row)
        }
    }

    /** "Send later": any day up to a year ahead and any time (owner decision, 2026-10-12); see [PlanDialog]. */
    private fun askWhenToSend() {
        val window = PlanDialog(this, palette, speak = { core.speakLine(it) }) { due -> if (core.plan(chatId, due)) stopEditing() }
        planForTest = window
        show(window.dialog)
    }

    /** "jeudi 15 octobre", in the app's language. */
    private fun dayName(millis: Long): String = Ui.date(this, millis, "EEEEdMMMM")

    /** A few large reactions; one goes out as one radio message. */
    private fun askForReaction(messageId: Long) {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), 0)
        }
        val picker = Actions.readable(AlertDialog.Builder(this).setTitle(R.string.react).setView(grid).setNegativeButton(R.string.cancel, null).create())
        AppCore.REACTIONS.chunked(3).forEachIndexed { line, emojis ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            emojis.forEachIndexed { i, emoji ->
                row.addView(Ui.button(this, palette, emoji, null).apply {
                    tag = "react:${line * 3 + i}"
                    textSize = 32f
                    minHeight = Ui.dp(this@ConversationActivity, 72)
                    setOnClickListener {
                        picker.dismiss()
                        core.react(messageId, emoji)
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = pad() / 2
                    bottomMargin = pad() / 2
                })
            }
            grid.addView(row)
        }
        // Spec decision 29: any one emoji from the phone's keyboard.
        grid.addView(Ui.button(this, palette, getString(R.string.react_other), R.drawable.ic_keyboard).apply {
            tag = "react_other"
            setOnClickListener { askForOtherEmoji(messageId) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = pad() / 2
        })
        show(picker)
    }

    /** One emoji typed with the phone's keyboard (its emoji panel); anything else is refused with a sentence. */
    private fun askForOtherEmoji(messageId: Long) {
        val field = EditText(this).apply {
            tag = "react_field"
            textSize = 32f
            isSingleLine = true
            gravity = Gravity.CENTER
            minHeight = Ui.dp(this@ConversationActivity, 72)
            contentDescription = getString(R.string.react_other)
        }
        val problem = Ui.text(this, getString(R.string.react_one_emoji), palette.text).apply {
            tag = "react_problem"
            visibility = View.GONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), 0)
            addView(field)
            addView(problem)
        }
        val ask = Actions.readable(AlertDialog.Builder(this).setTitle(R.string.react_other).setView(box)
            .setPositiveButton(R.string.react, null).setNegativeButton(R.string.cancel, null).create())
        ask.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        show(ask)
        field.requestFocus()
        ask.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val emoji = org.phonemesh.core.wire.Reactions.single(field.text.toString())
            if (emoji == null) {
                problem.visibility = View.VISIBLE
                core.speakLine(getString(R.string.react_one_emoji))
            } else {
                ask.dismiss()
                core.react(messageId, emoji)
            }
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13 and later show their own notice.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) core.note(UiText(R.string.copied))
    }

    /** The picture as the bubble shows it: the view saved before, or the received one four times larger, smoothed. */
    private fun pictureForExport(messageId: Long): android.graphics.Bitmap? =
        if (core.chosenLook(messageId) != null) core.pictureShown(messageId)
        else core.pictureBitmap(messageId)?.let {
            android.graphics.Bitmap.createScaledBitmap(it, it.width * PictureLooks.SCALE, it.height * PictureLooks.SCALE, true)
        }

    private fun savePicture(messageId: Long) {
        val bitmap = pictureForExport(messageId) ?: return
        core.note(UiText(if (PictureExport.save(this, bitmap) != null) R.string.picture_saved else R.string.picture_save_failed))
    }

    private fun sharePicture(messageId: Long) {
        val bitmap = pictureForExport(messageId) ?: return
        if (!PictureExport.share(this, bitmap)) core.note(UiText(R.string.picture_save_failed))
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    /** The words go to the other chat's draft; that chat opens to hear and send them. */
    /** Each chat with its picture beside its name, as in the chat list, so it can be found without reading. */
    private fun askWhereToForward(messageId: Long, chats: List<AppCore.ChatRow>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad() / 2, pad(), 0)
        }
        val choose = Actions.readable(AlertDialog.Builder(this).setTitle(R.string.forward_to).setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(R.string.cancel, null).create())
        for (row in chats) {
            list.addView(LinearLayout(this).apply {
                tag = "forward_to:${row.chat.id}"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(this@ConversationActivity, 56)
                isClickable = true
                isFocusable = true
                contentDescription = row.title
                background = Ui.ripple(GradientDrawable(), Ui.pressedOf(palette.text))
                addView(Looks.avatar(this@ConversationActivity, palette, row.chat.kind, 40))
                addView(Ui.text(this@ConversationActivity, row.title, palette.text, bold = true).apply {
                    setPadding(pad(), 0, 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    choose.dismiss()
                    if (core.history.message(messageId)?.kind == MessageKind.PICTURE) {
                        startActivity(PictureActivity.forwarding(this@ConversationActivity, row.chat.id, messageId))
                    } else if (core.forward(messageId, row.chat.id)) startActivity(intent(this@ConversationActivity, row.chat.id))
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        show(choose)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    // Test hook
    internal val dialogForTest: AlertDialog? get() = dialog?.takeIf { it.isShowing }
    internal var planForTest: PlanDialog? = null
        private set

    companion object {
        const val EXTRA_CHAT_ID = "chat"
        const val MIC_DP = 80
        /** The height of messages that stays in view above a draft or a tip. */
        const val MESSAGES_KEPT_DP = 96
        /** Top bar, the microphone bar, a large tip on a 360 dp wide screen and the messages kept. */
        const val ROOMY_DP = 560
        private const val STATE_EDITING = "editing"
        private const val REQUEST_MIC = 1

        fun intent(context: Context, chatId: Long): Intent =
            Intent(context, ConversationActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
    }
}
