package org.phonemesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
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
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.DraftFit
import org.phonemesh.core.DraftRules
import org.phonemesh.core.PeopleHeard
import org.phonemesh.core.RecordGesture
import org.phonemesh.core.Tip
import org.phonemesh.core.TipFacts
import org.phonemesh.core.TipPlace
import org.phonemesh.core.Tips
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
    private lateinit var cutNotice: View
    private lateinit var cutPanel: HowToPanel
    private lateinit var micBar: LinearLayout
    private lateinit var mic: FrameLayout
    private lateinit var micIcon: ImageView
    private lateinit var micLabel: TextView
    private lateinit var ring: RecordRing
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

    /** Typing and pasting stop at one radio part; deleting is always allowed. */
    private val fitFilter = InputFilter { source, start, end, dest, dstart, dend ->
        if (end == start) return@InputFilter null
        val tag = core.currentLanguage().tag
        val before = dest.subSequence(0, dstart).toString()
        val after = dest.subSequence(dend, dest.length).toString()
        fun fits(count: Int): Boolean {
            // An invalid half-surrogate is not evidence that a longer valid prefix fits.
            val boundary = if (count > 0 && Character.isHighSurrogate(source[start + count - 1])) count - 1 else count
            return DraftRules.fit(
                (before + source.subSequence(start, start + boundary) + after).replace('\n', ' '), tag,
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
        root.addView(fitted(draftArea(), tipBox))
        bars.forEach { root.addView(it) }
        show(root)
        built = true
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
        // A filled round button like the microphone, so it is found at a glance; renderDraftState colours it.
        playFill = Ui.circle(palette.accent).apply { setStroke(Ui.dp(this@ConversationActivity, 2), palette.outline) }
        playDraft = Ui.iconButton(this, palette, R.drawable.ic_play, getString(R.string.listen), 64).apply {
            tag = "draft_play"
            background = Ui.ripple(playFill, Ui.pressedOf(palette.onAccent))
            setOnClickListener {
                if (tip == Tip.LISTEN) retireTip()
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
                    RecordGesture.Phase.LOCKED -> { pressing = false; lockRecording() }
                    else -> cancelHint.alpha = 1f - 0.6f * RecordGesture.cancelProgress(event.rawX - downX, density)
                }
            }
            MotionEvent.ACTION_UP -> if (pressing) {
                pressing = false
                when (RecordGesture.onRelease(event.rawX - downX, event.rawY - downY, density, event.eventTime - downAt)) {
                    RecordGesture.Phase.LOCKED -> lockRecording()
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

    private fun lockRecording() {
        core.recordLocked = true
        if (tip == Tip.LOCK) retireTip()
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
        if (!isChangingConfigurations) retireTip(owner.settings)
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
        val messages = core.history.messages(chatId)
        bubbles.update(chat, messages)
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
            // One picture at a time: while the cut-off notice shows, the draft's tip waits.
            drafting -> if (core.draftFit(chatId) == DraftFit.EMPTY || cutNotice.visibility == View.VISIBLE) null else TipPlace.DRAFT
            else -> TipPlace.CHAT
        }, messages)
    }

    /** Keep the same picture through renders; leaving its moment retires it. */
    private fun renderTip(place: TipPlace?, messages: List<Message>) {
        tip?.let { if (it.place != place) retireTip() }
        // The first-message hint owns this space while the microphone is idle.
        if (tip == null && place != null && (place != TipPlace.CHAT || core.settings.firstMessageSent)) {
            val facts = TipFacts(
                sent = messages.any { it.direction == Direction.OUT },
                failed = messages.any { it.status?.canResend == true },
                received = messages.any { it.direction == Direction.IN },
                direct = core.history.chat(chatId)?.kind != ChatKind.GROUP,
            )
            Tips.next(place, facts, core.settings.tipsShown)?.let { showTip(it) }
        }
        (tipBox.getChildAt(0) as? HowToPanel)?.update()
        tipBox.visibility = if (tip != null) View.VISIBLE else View.GONE
    }

    private fun showTip(next: Tip) {
        tip = next
        tipBox.removeAllViews()
        // The picture as wide as the card, like a setup step; the sentence, speaker and close sit under it.
        val card = howTo(next.key, compact = !roomy).apply {
            tag = "tip:${next.key}"
            setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
            background = Ui.outline(this@ConversationActivity, palette.surface, palette.accent, strokeDp = 3)
        }
        card.add(Ui.iconButton(this, palette, R.drawable.ic_close, getString(R.string.tip_close)).apply {
            tag = "tip_close"
            setOnClickListener { retireTip(); render() }
        })
        tipBox.addView(card)
    }

    /** Persist on close, action or moment end, not merely on display. */
    private fun retireTip(settings: Settings = core.settings) {
        val seen = tip ?: return
        settings.tipsShown = settings.tipsShown + seen.key
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
        micHint.visibility = if (!core.settings.firstMessageSent && !recordingHere) View.VISIBLE else View.GONE
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
            setOnClickListener {
                if (tip == Tip.ARRIVE && message.direction == Direction.IN) retireTip()
                core.play(message.id)
            }
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
                // Off like Send while the radio has no region or its region is being set.
                Ui.enable(this, !core.region.blocksSending)
                setOnClickListener {
                    if (tip == Tip.FAILED) retireTip()
                    core.resend(message.id)
                }
            })
        }
        row.addView(box)
        return row
    }

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
