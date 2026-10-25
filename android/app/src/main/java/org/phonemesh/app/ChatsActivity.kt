package org.phonemesh.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup

import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.CatalogEntry
import org.phonemesh.core.GeneratedPackCatalog
import org.phonemesh.core.PackRejected
import org.phonemesh.core.Readiness
import org.phonemesh.core.RegionStep
import org.phonemesh.core.SetupPlan
import org.phonemesh.core.SetupStep
import org.phonemesh.core.SetupSteps
import org.phonemesh.core.SetupView
import java.util.Date

/**
 * Home: radio status, then either the numbered setup guide with how-to pictures and the friend guide, alone, or
 * (after setup) single problem cards, every chat and ➕ for a private chat.
 */
class ChatsActivity : ScreenActivity() {
    private lateinit var radioStatus: ImageView
    private lateinit var cards: LinearLayout
    private lateinit var emptyHint: LinearLayout
    private lateinit var setupScroll: ScrollView
    /** The chat list, its empty hint and ➕. */
    private lateinit var chatsFrame: FrameLayout
    /** The numbered guide is showing: nothing else is, and it may use the whole height. */
    private var guiding = false
    private val rows = ChatAdapter()
    private var built = false
    private var importDialog: AlertDialog? = null
    /** The pack waiting for "Installer"; kept across rotation, dropped when answered. */
    private var pendingImport: Uri? = null
    /** First launch: a pack intent is waiting for the language to be chosen, so the recreate after it must replay it. */
    private var importWaitsForLanguage = false

    private var doneUntil = 0L
    /** One panel per step or problem, kept so renders never restart a picture. */
    private val panels = HashMap<String, HowToPanel>()
    private val rerender = Runnable { render() }
    /** One region picture for the screen, so renders never restart it. */
    private val regionPicture by lazy { RegionPicture(this) }
    /** The connection (AppCore.readyReports) the country list was last opened for by itself. */
    private var askedCountryFor = -1
    /** The voice step shows how to get the voice from a friend, in place of its two choices; kept across rotation. */
    private var friendGuide = false
    /** The guide's pictures, with and without the choice's number, kept so renders never restart them. */
    private val friendGuides = HashMap<Boolean, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar())
        root.addView(noteLine())
        cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), 0)
        }
        // Tall problem cards must not squeeze the chats to zero; they remain scrollable. The guide has the screen to itself.
        setupScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = if (guiding || View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) heightMeasureSpec
                else View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(heightMeasureSpec) * 3 / 4, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, height)
            }
        }.apply {
            tag = "setup_scroll"
            addView(cards)
        }
        root.addView(setupScroll)
        lateinit var list: ListView
        // The hint is centred, but tall problem cards leave little room: it moves below the chat rows, or out of sight.
        val frame = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                if (emptyHint.visibility != View.VISIBLE) return
                val rowsEnd = (0 until list.childCount).maxOfOrNull { list.getChildAt(it).bottom } ?: 0
                if (emptyHint.top < rowsEnd) emptyHint.offsetTopAndBottom(rowsEnd - emptyHint.top)
                if (emptyHint.bottom > height) emptyHint.offsetTopAndBottom(height - emptyHint.top)
            }
        }
        list = ListView(this).apply {
            tag = "chats"
            adapter = rows
            divider = null
            setOnItemClickListener { _, _, position, _ -> openChat(rows.getItem(position).chat.id) }
            setOnItemLongClickListener { _, _, position, _ -> showChatActions(rows.getItem(position).chat); true }
        }
        frame.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        emptyHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            tag = "empty"
            visibility = View.GONE
            addView(Ui.icon(this@ChatsActivity, R.drawable.ic_chat, palette.muted, 64))
            addView(Ui.text(this@ChatsActivity, getString(R.string.no_messages), palette.muted, bold = true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        frame.addView(emptyHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { marginStart = pad() * 2; marginEnd = pad() * 2 })
        val add = Ui.iconButton(this, palette, R.drawable.ic_plus, getString(R.string.new_private_chat), 64, palette.background).apply {
            tag = "new_chat"
            background = Ui.circle(palette.text)
            elevation = Ui.dp(this@ChatsActivity, 4).toFloat()
            setOnClickListener { startActivity(Intent(this@ChatsActivity, AddSomeoneActivity::class.java)) }
        }
        frame.addView(add, FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.END or Gravity.BOTTOM)
            .apply { setMargins(pad(), pad(), pad(), pad()) })
        chatsFrame = frame
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        show(root)
        built = true
        friendGuide = savedInstanceState?.getBoolean(STATE_FRIEND_GUIDE) == true
        if (core.settings.language == null) {
            // First launch: onStart shows the language screen; this screen recreates in the chosen language (ScreenActivity.onStart).
            importWaitsForLanguage = true
            return
        }
        // A recreate (rotation, process death) must not replay the intent that already ran; only the recreate after the
        // first-launch language choice does, and a question that was open comes back.
        askedCountryFor = savedInstanceState?.getInt(STATE_ASKED_COUNTRY, -1) ?: -1
        val waited = savedInstanceState?.getBoolean(STATE_WAITS_FOR_LANGUAGE) == true
        if (savedInstanceState == null || waited) openFromIntent(intent)
        else savedInstanceState.getString(STATE_PENDING_IMPORT)?.let { askToInstall(Uri.parse(it)) }
    }

    override fun onStart() {
        super.onStart()
        // Also reached after a pack intent destroyed the language screen on top of this one: ask again.
        if (core.settings.language == null) startActivity(Intent(this, LanguageActivity::class.java))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WAITS_FOR_LANGUAGE, importWaitsForLanguage)
        outState.putInt(STATE_ASKED_COUNTRY, askedCountryFor)
        outState.putBoolean(STATE_FRIEND_GUIDE, friendGuide)
        pendingImport?.let { outState.putString(STATE_PENDING_IMPORT, it.toString()) }
    }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@ChatsActivity, 64)
            setPadding(pad() / 2, 0, pad() / 2, 0)
        }
        radioStatus = ImageView(this).apply {
            tag = "radio_status"
            isClickable = true
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { openRadio() }
        }
        bar.addView(radioStatus, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        bar.addView(Ui.text(this, getString(R.string.chats_title), palette.text, 24f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() / 2 })
        bar.addView(Ui.iconButton(this, palette, R.drawable.ic_help, getString(R.string.help)).apply {
            tag = "help"
            setOnClickListener { startActivity(Intent(this@ChatsActivity, HelpActivity::class.java)) }
        })
        bar.addView(Ui.iconButton(this, palette, R.drawable.ic_gear, getString(R.string.settings)).apply {
            tag = "settings"
            setOnClickListener { startActivity(Intent(this@ChatsActivity, SettingsActivity::class.java)) }
        })
        return bar
    }

    override fun render() {
        if (!built) return
        Looks.radio(radioStatus, palette, core.connected)
        val guide = renderCards()
        if (guide != guiding) {
            guiding = guide
            setupScroll.requestLayout()
        }
        // During setup only the steps show. The chats, their hint and ➕ come with the green card that ends it.
        chatsFrame.visibility = if (guide) View.GONE else View.VISIBLE
        val data = core.chatRows()
        rows.items = data
        rows.notifyDataSetChanged()
        emptyHint.visibility = if (data.all { it.last == null }) View.VISIBLE else View.GONE
    }

    /** A "Connecting to…" card was shown; when the radio answers, a green "Connected" card shows for a moment. */
    private var reconnectShown = false
    private var linkedUntil = 0L

    /** The radio's state as one card: the round mark and its sentence. Tapping it opens the Radio screen. */
    private fun radioLinkCard(link: RadioLink): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = "radio_link_card:${link.name.lowercase()}"
        val inner = Ui.dp(this@ChatsActivity, 12)
        setPadding(inner, inner, inner, inner)
        background = Ui.outline(this@ChatsActivity, palette.surface, if (link == RadioLink.CONNECTED) palette.good else palette.outline)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(this@ChatsActivity, 8)
        }
        addView(RadioBadge.view(this@ChatsActivity, palette, link, 48), LinearLayout.LayoutParams(Ui.dp(this@ChatsActivity, 48), Ui.dp(this@ChatsActivity, 48)))
        val words = if (link == RadioLink.CONNECTED) getString(R.string.radio_connected_to, RadioBadge.name(core))
        else getString(R.string.radio_connecting_to, RadioBadge.name(core))
        addView(Ui.text(this@ChatsActivity, words, palette.text, 18f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = inner })
        contentDescription = words
        isClickable = true
        setOnClickListener { openRadio() }
    }

    /** Numbered steps, then two seconds of green; afterwards slim resource/new-step cards. True while the guide shows. */
    private fun renderCards(): Boolean {
        cards.removeAllViews()
        cards.removeCallbacks(rerender)
        val now = SystemClock.uptimeMillis()
        var connectingShown = false
        val missing = core.missingVoicePacks()
        val voiceReady = missing.isEmpty() && core.readiness() is Readiness.Ready
        if (voiceReady) friendGuide = false
        val settings = core.settings
        // A direct radio needs its region settled; an authenticated hub provides the communal-radio route instead.
        val region = RegionCards.body(this, palette, core, regionPicture)
        var regionShown = false
        val view = SetupSteps.of(voiceReady, core.account() != null, core.setupConnectionReady(), settings.firstMessageSent, settings.setupFinished, core.hasAddedSomeone())
        when (view) {
            is SetupView.Guide -> {
                regionShown = view.current == SetupStep.RADIO && region != null
                cards.addView(
                    if (region != null && regionShown) StepCard.current(this, palette, view.current, view.done.size, stepTitle(view.current), regionPicture, null, region)
                    else if (view.current == SetupStep.VOICE && friendGuide) StepCard.current(this, palette, view.current, view.done.size,
                        stepTitle(view.current), friendGuideView(numbered = true), null, friendGuideActions())
                    else StepCard.current(this, palette, view.current, view.done.size, stepTitle(view.current),
                        panel(StepCard.scenes(view.current), view.current), stepDetail(view.current, missing), stepActions(view.current, missing, numbered = true)))
                SetupSteps.ORDER.filter { it != view.current }.forEach { step ->
                    val done = step in view.done
                    cards.addView(StepCard.row(this, palette, step, done, stepTitle(step), if (done) doneDetail(step) else stepHint(step, missing)))
                }
            }
            SetupView.Complete -> {
                settings.setupFinished = true
                doneUntil = now + DONE_MS
            }
            is SetupView.Problems -> view.lost.forEach { step ->
                if (step == SetupStep.RADIO && region != null) {
                    regionShown = true
                    cards.addView(StepCard.problem(this, palette, step, stepTitle(step), regionPicture, region))
                } else if (step == SetupStep.RADIO && RadioBadge.linkOf(core) == RadioLink.CONNECTING) {
                    // The app is still reconnecting the remembered radio by itself: say so, not "find the radio".
                    connectingShown = true
                    cards.addView(radioLinkCard(RadioLink.CONNECTING))
                } else if (step == SetupStep.VOICE && friendGuide) {
                    cards.addView(StepCard.problem(this, palette, step, stepTitle(step), friendGuideView(numbered = false), friendGuideActions()))
                } else {
                    // One picture without numbered parts, so the two choices carry no numbers either.
                    cards.addView(StepCard.problem(this, palette, step, stepTitle(step), panel(listOf(StepCard.problemScene(step)), step),
                        stepActions(step, missing, numbered = false)))
                }
            }
            SetupView.Hidden -> Unit
        }
        // No country known: the list opens by itself, once per connection. Choosing there writes nothing.
        if (regionShown && core.region.phase == RegionPhase.Idle && core.region.step() == RegionStep.ChooseCountry &&
            askedCountryFor != core.readyReports) {
            askedCountryFor = core.readyReports
            startActivity(RegionActivity.countries(this))
        }
        when {
            connectingShown -> reconnectShown = true
            reconnectShown && core.connected -> { reconnectShown = false; linkedUntil = now + LINKED_MS }
            !core.connected -> { reconnectShown = false; linkedUntil = 0L }
        }
        if (now < linkedUntil) {
            cards.addView(radioLinkCard(RadioLink.CONNECTED), 0)
            cards.postDelayed(rerender, linkedUntil - now)
        }
        if (now < doneUntil) {
            cards.addView(doneCard())
            cards.postDelayed(rerender, doneUntil - now)
        }
        return view is SetupView.Guide
    }

    /** Reuse each panel and refresh its speaker when a voice arrives. */
    private fun panel(scenes: List<String>, step: SetupStep): HowToPanel =
        panels.getOrPut("${step.name}:${scenes.joinToString(",")}") {
            // NAME reuses the microphone picture, not HELLO's greeting instruction or recorded clip.
            if (step == SetupStep.NAME) howTo(*scenes.toTypedArray(), sentence = { getString(R.string.name_say) })
            else howTo(*scenes.toTypedArray())
        }.also { it.update() }

    private fun stepTitle(step: SetupStep) = getString(when (step) {
        SetupStep.VOICE -> R.string.step_voice
        SetupStep.NAME -> R.string.step_name
        SetupStep.RADIO -> R.string.setup_radio_title
        SetupStep.ADD -> R.string.add_title
        SetupStep.HELLO -> R.string.step_hello
    })

    private fun stepDetail(step: SetupStep, missing: List<CatalogEntry>): String? =
        if (step == SetupStep.VOICE && missing.isNotEmpty()) {
            getString(R.string.setup_voice_card, getString(R.string.setup_voice_title), SetupPlan.megabytes(missing))
        } else {
            null
        }

    private fun stepHint(step: SetupStep, missing: List<CatalogEntry>) = when (step) {
        SetupStep.VOICE -> stepDetail(step, missing) ?: getString(R.string.setup_voice_title)
        SetupStep.NAME -> getString(R.string.name_say)
        SetupStep.RADIO -> getString(R.string.setup_radio_hint)
        SetupStep.ADD -> getString(R.string.step_add_hint)
        SetupStep.HELLO -> getString(R.string.step_hello_hint)
    }

    private fun doneDetail(step: SetupStep) = when (step) {
        SetupStep.VOICE -> GeneratedPackCatalog.entries.firstOrNull { it.id == core.currentLanguage().voicePackId }
            ?.let { getString(R.string.step_voice_done, getString(R.string.setup_voice_title), SetupPlan.megabytes(listOf(it))) }
            ?: getString(R.string.setup_voice_title)
        SetupStep.NAME -> core.account()?.first.orEmpty()
        SetupStep.RADIO -> getString(R.string.radio_connected)
        SetupStep.ADD -> getString(R.string.step_add_done)
        SetupStep.HELLO -> getString(R.string.step_hello_done)
    }

    private fun stepActions(step: SetupStep, missing: List<CatalogEntry>, numbered: Boolean): View = when (step) {
        SetupStep.VOICE -> voiceActions(missing, numbered)
        SetupStep.NAME -> Ui.button(this, palette, getString(R.string.step_name_button), R.drawable.ic_mic, filled = true).apply {
            tag = "name_open"
            setOnClickListener { NameActivity.open(this@ChatsActivity) }
        }.let { padded(it) }
        SetupStep.RADIO -> Ui.button(this, palette, getString(R.string.setup_search), R.drawable.ic_search, filled = true).apply {
            tag = "radio_search"
            setOnClickListener { openRadio() }
        }.let { padded(it) }
        SetupStep.ADD -> Ui.button(this, palette, getString(R.string.add_title), R.drawable.ic_plus, filled = true).apply {
            tag = "setup_add"
            setOnClickListener { startActivity(Intent(this@ChatsActivity, AddSomeoneActivity::class.java)) }
        }.let { padded(it) }
        SetupStep.HELLO -> Ui.button(this, palette, getString(R.string.step_hello_button), R.drawable.ic_mic, filled = true).apply {
            tag = "hello_try"
            setOnClickListener { openChat(core.groupChatId()) }
        }.let { padded(it) }
    }

    /** A choice's name; in the numbered guide it starts with the number of its part of the step's picture. */
    private fun choice(number: Int, name: Int, numbered: Boolean): String =
        if (numbered) getString(R.string.setup_option, number, getString(name)) else getString(name)

    /**
     * The step's two choices: 1 downloads (a running download shows its progress there instead), 2 opens the
     * friend guide and is always on offer. Without internet the friend is the filled button.
     */
    private fun voiceActions(missing: List<CatalogEntry>, numbered: Boolean): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad() / 2, 0, 0) }
        val full = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val percent = core.voiceDownloadPercent()
        val choices = SetupPlan.voiceChoices(missing.isNotEmpty(), percent != null, core.access.noInternet())
        if (percent != null) {
            box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "voice_bar"
                max = 100
                progress = percent
                progressTintList = ColorStateList.valueOf(palette.text)
            }, full())
            box.addView(Ui.text(this, getString(R.string.progress_percent, percent), palette.text, bold = true).apply { tag = "voice_progress" })
        }
        if (choices.download) {
            box.addView(Ui.button(this, palette, choice(1, R.string.setup_download, numbered), R.drawable.ic_download, filled = !choices.friendFirst).apply {
                tag = "voice_download"
                setOnClickListener { core.downloadVoice(); render() }
            }, full())
        }
        box.addView(Ui.button(this, palette, choice(2, R.string.setup_file, numbered), R.drawable.ic_person, filled = choices.friendFirst).apply {
            tag = "voice_file"
            setOnClickListener { friendGuide = true; render() }
        }, full().apply { if (box.childCount > 0) topMargin = pad() / 2 })
        return box
    }

    /** The friend guide: the choice's name, then two small pictures with one sentence each. Opens nothing by itself. */
    private fun friendGuideView(numbered: Boolean): View = friendGuides.getOrPut(numbered) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "voice_guide"
            addView(Ui.text(this@ChatsActivity, choice(2, R.string.setup_file, numbered), palette.text, bold = true))
            listOf("friend" to R.string.setup_guide_send, "allow" to R.string.setup_guide_accept).forEach { (scene, words) ->
                addView(howTo(scene, compact = true, sentence = { getString(words) }),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 2 })
            }
        }
    }.also { guide -> (guide as ViewGroup).let { group -> (0 until group.childCount).forEach { (group.getChildAt(it) as? HowToPanel)?.update() } } }

    /** The lesser way (the file picker, for a file already on the phone) and the way back to the two choices. */
    private fun friendGuideActions(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad() / 2, 0, 0) }
        val full = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        box.addView(Ui.button(this, palette, getString(R.string.setup_choose_file), R.drawable.ic_folder).apply {
            tag = "voice_pick"
            setOnClickListener { pickFile() }
        }, full())
        box.addView(Ui.button(this, palette, getString(R.string.back), R.drawable.ic_back).apply {
            tag = "voice_guide_back"
            setOnClickListener { closeFriendGuide() }
        }, full().apply { if (box.childCount > 0) topMargin = pad() / 2 })
        return box
    }

    private fun closeFriendGuide() {
        friendGuide = false
        render()
    }

    /** Back leaves the friend guide before it leaves the screen. */
    @Deprecated("Platform callback")
    override fun onBackPressed() {
        if (friendGuide) closeFriendGuide() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun padded(button: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, pad() / 2, 0, 0)
        addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun doneCard() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tag = "setup_done"
        setPadding(pad(), pad(), pad(), pad())
        background = Ui.outline(this@ChatsActivity, palette.background, palette.good, strokeDp = 3)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = pad() / 2 }
        addView(Ui.icon(this@ChatsActivity, R.drawable.ic_check, palette.good, 32))
        addView(Ui.text(this@ChatsActivity, getString(R.string.setup_done), palette.good, bold = true).apply { setPadding(pad() / 2, 0, 0, 0) })
    }

    private inner class ChatAdapter : BaseAdapter() {
        var items: List<AppCore.ChatRow> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].chat.id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val row = chatRow(item)
            // Radio-addressed chats from before accounts come last, under their own heading (plan step 12).
            if (!item.old || (position > 0 && items[position - 1].old)) return row
            return LinearLayout(this@ChatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(this@ChatsActivity, getString(R.string.old_chats), palette.muted, bold = true).apply {
                    tag = "old_chats"
                    setPadding(pad(), pad(), pad(), 0)
                })
                addView(row)
            }
        }
    }

    private fun chatRow(item: AppCore.ChatRow): View {
        val chat = item.chat
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@ChatsActivity, 76)
            setPadding(pad(), pad() / 2, pad(), pad() / 2)
            tag = "chat:${chat.id}"
            contentDescription = item.title
        }
        row.addView(Looks.avatar(this, palette, chat.kind, 56, item.colour))
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad() / 2, 0)
        }
        val titleLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleLine.addView(Ui.content(Ui.text(this, item.title, palette.text, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (chat.pinned) {
            titleLine.addView(Ui.icon(this, R.drawable.ic_pin, palette.muted, 20).apply {
                tag = "pinned:${chat.id}"
                contentDescription = getString(R.string.pinned)
            })
        }
        item.peopleHeard?.let { heard ->
            titleLine.addView(Looks.peopleBadge(this, palette).apply {
                tag = "people:${chat.id}"
                Looks.showPeople(this, heard)
            })
        }
        middle.addView(titleLine)
        if (item.met == false) middle.addView(Ui.text(this, getString(R.string.not_met), palette.muted).apply { tag = "not_met:${chat.id}" })
        item.last?.let { last ->
            val lastLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val hasIcon = last.status != null
            last.status?.let { status ->
                val look = Looks.status(status, palette)
                lastLine.addView(Looks.statusIcon(this, look, 20).apply { tag = "last_status:${chat.id}" })
            }
            val prefix = when {
                chat.kind != ChatKind.GROUP -> null
                last.direction == Direction.OUT -> getString(R.string.you)
                else -> core.senderName(last.senderId.orEmpty())
            }
            val said = if (last.kind == MessageKind.PICTURE) getString(R.string.picture_label) else last.text
            val text = if (prefix != null) getString(R.string.sender_prefix, prefix, said) else said
            lastLine.addView(Ui.content(Ui.text(this, text, palette.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (hasIcon) marginStart = Ui.dp(this@ChatsActivity, 4) })
            middle.addView(lastLine)
        }
        row.addView(middle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        item.last?.let { right.addView(Ui.text(this, timeOf(it.time), palette.muted)) }
        if (chat.unread > 0) {
            right.addView(Ui.text(this, chat.unread.toString(), palette.background, bold = true).apply {
                tag = "unread:${chat.id}"
                contentDescription = getString(R.string.unread, chat.unread)
                gravity = Gravity.CENTER
                background = Ui.circle(palette.good)
                minWidth = Ui.dp(this@ChatsActivity, 32)
                minHeight = Ui.dp(this@ChatsActivity, 32)
            })
        }
        row.addView(right)
        return row
    }

    private fun timeOf(time: Long): String =
        (if (DateUtils.isToday(time)) DateFormat.getTimeFormat(this) else DateFormat.getDateFormat(this)).format(Date(time))

    private fun openChat(chatId: Long) = startActivity(ConversationActivity.intent(this, chatId))

    private fun openRadio() = startActivity(Intent(this, RadioActivity::class.java))


    private fun pickFile() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
            // The Downloads provider can omit ZIPs copied over USB; start in the filesystem provider's Download folder.
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
        }, REQUEST_FILE)
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK) data?.data?.let { core.importPack(it) }
    }

    /**
     * A ZIP opened or shared from another app (file manager, browser downloads, messaging). Any app can
     * send one, so nothing is installed until the user confirms; AppCore refuses it while recording.
     */
    private fun openFromIntent(intent: Intent?) {
        if (intent == null || (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND)) return
        setIntent(Intent(this, ChatsActivity::class.java)) // handled once; never replayed
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (core.recordState != AppCore.RecordState.IDLE) { core.note(UiText(R.string.wait_for_operation)); return }
        val uri = if (intent.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else intent.data
        // Only content the sender shares with us; a file path could point into this app's own storage.
        if (uri == null || uri.scheme != "content") {
            core.note(PackMessages.forFailure(PackRejected(PackRejected.Reason.UNSAFE, "not content")))
            return
        }
        askToInstall(uri)
    }

    private fun askToInstall(uri: Uri) {
        importDialog?.dismiss()
        pendingImport = uri
        val dialog = AlertDialog.Builder(this).setTitle(R.string.install_pack_title)
            .setPositiveButton(R.string.install) { _, _ -> core.importPack(uri) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { if (importDialog === dialog) { importDialog = null; pendingImport = null } }
        importDialog = dialog
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        // LinkActivity has finished: publish its result only after this screen can show it.
        val notice = intent.getIntExtra(LinkActivity.EXTRA_NOTICE, 0)
        intent.removeExtra(LinkActivity.EXTRA_NOTICE)
        // This launcher is exported. Never treat arbitrary external integers as resource IDs.
        if (notice in setOf(R.string.add_bad_code, R.string.add_name_first, R.string.add_own_code, R.string.mail_save_failed)) {
            core.note(UiText(notice))
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(LinkActivity.EXTRA_NOTICE)) setIntent(intent)
        if (core.settings.language == null) setIntent(intent) else openFromIntent(intent)
    }

    private var chatDialog: AlertDialog? = null

    private fun showChatDialog(next: AlertDialog) {
        chatDialog?.dismiss()
        next.setOnDismissListener { if (chatDialog === next) chatDialog = null }
        chatDialog = next
        next.show()
    }

    /** A long press on a chat: pin a private chat to the top (the group is always first), or delete it on this phone. */
    private fun showChatActions(chat: Chat) {
        showChatDialog(Actions.menu(this, palette, listOfNotNull(
            if (chat.kind == ChatKind.GROUP) null else if (chat.pinned) {
                Actions.Item("action_unpin", R.string.unpin_chat, R.drawable.ic_pin) { core.pinChat(chat.id, false) }
            } else {
                Actions.Item("action_pin", R.string.pin_chat, R.drawable.ic_pin) { core.pinChat(chat.id, true) }
            },
            Actions.Item("action_delete_chat", R.string.delete_chat, R.drawable.ic_delete) {
                showChatDialog(Actions.confirm(this, R.string.delete_chat_title, R.string.delete_only_here, R.string.delete) {
                    core.deleteChat(chat.id)
                })
            },
        )))
    }

    // Test hook
    internal val chatDialogForTest: AlertDialog? get() = chatDialog?.takeIf { it.isShowing }

    override fun onDestroy() {
        chatDialog?.dismiss()
        importDialog?.dismiss() // a pending import is dropped, never installed

        super.onDestroy()
    }

    // Test hook
    internal val importDialogForTest: AlertDialog? get() = importDialog?.takeIf { it.isShowing }


    private companion object {
        const val STATE_WAITS_FOR_LANGUAGE = "waits_for_language"
        const val STATE_PENDING_IMPORT = "pending_import"
        const val STATE_ASKED_COUNTRY = "asked_country"
        const val STATE_FRIEND_GUIDE = "friend_guide"
        const val REQUEST_FILE = 101
        const val DONE_MS = 2_000L
        /** How long the green "Connected" card shows after a reconnection the screen was waiting for. */
        const val LINKED_MS = 3_000L
    }
}
