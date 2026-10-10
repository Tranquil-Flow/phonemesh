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
import android.widget.ArrayAdapter
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
import org.phonemesh.core.SetupPlan
import org.phonemesh.core.SetupStep
import org.phonemesh.core.SetupSteps
import org.phonemesh.core.SetupView
import java.util.Date

/** Home: radio status, the numbered setup guide with how-to pictures (then single problem cards), every chat, ➕ for a private chat. */
class ChatsActivity : ScreenActivity() {
    private lateinit var radioStatus: ImageView
    private lateinit var cards: LinearLayout
    private lateinit var emptyHint: LinearLayout
    private val rows = ChatAdapter()
    private var built = false
    private var importDialog: AlertDialog? = null
    /** The pack waiting for "Installer"; kept across rotation, dropped when answered. */
    private var pendingImport: Uri? = null
    /** First launch: a pack intent is waiting for the language to be chosen, so the recreate after it must replay it. */
    private var importWaitsForLanguage = false
    private var peerDialog: AlertDialog? = null
    private var doneUntil = 0L
    /** One panel per step or problem, kept so renders never restart a picture. */
    private val panels = HashMap<String, HowToPanel>()
    private val rerender = Runnable { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(topBar())
        root.addView(noteLine())
        cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), 0)
        }
        // A tall guide must not squeeze the chats to zero. Its pictures and later steps remain scrollable.
        val setupScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) heightMeasureSpec
                else View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(heightMeasureSpec) * 3 / 4, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, height)
            }
        }.apply {
            tag = "setup_scroll"
            addView(cards)
        }
        root.addView(setupScroll)
        val frame = FrameLayout(this)
        val list = ListView(this).apply {
            tag = "chats"
            adapter = rows
            divider = null
            setOnItemClickListener { _, _, position, _ -> openChat(rows.getItem(position).chat.id) }
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
            setOnClickListener { pickPeer() }
        }
        frame.addView(add, FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.END or Gravity.BOTTOM)
            .apply { setMargins(pad(), pad(), pad(), pad()) })
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        show(root)
        built = true
        if (core.settings.language == null) {
            // First launch: onStart shows the language screen; this screen recreates in the chosen language (ScreenActivity.onStart).
            importWaitsForLanguage = true
            return
        }
        // A recreate (rotation, process death) must not replay the intent that already ran; only the recreate after the
        // first-launch language choice does, and a question that was open comes back.
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
        renderCards()
        val data = core.chatRows()
        rows.items = data
        rows.notifyDataSetChanged()
        emptyHint.visibility = if (data.all { it.last == null }) View.VISIBLE else View.GONE
    }

    /** Three numbered steps, then two seconds of green; afterwards only lost-voice/radio cards. */
    private fun renderCards() {
        cards.removeAllViews()
        cards.removeCallbacks(rerender)
        val now = SystemClock.uptimeMillis()
        val missing = core.missingVoicePacks()
        val voiceReady = missing.isEmpty() && core.readiness() is Readiness.Ready
        val settings = core.settings
        when (val view = SetupSteps.of(voiceReady, core.connected, settings.firstMessageSent, settings.setupFinished)) {
            is SetupView.Guide -> {
                cards.addView(StepCard.current(this, palette, view.current, view.done.size, stepTitle(view.current),
                    panel(StepCard.scenes(view.current)), stepDetail(view.current, missing), stepActions(view.current, missing)))
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
                cards.addView(StepCard.problem(this, palette, step, stepTitle(step), panel(listOf(StepCard.problemScene(step))), stepActions(step, missing)))
            }
            SetupView.Hidden -> Unit
        }
        if (now < doneUntil) {
            cards.addView(doneCard())
            cards.postDelayed(rerender, doneUntil - now)
        }
    }

    /** Reuse each panel and refresh its speaker when a voice arrives. */
    private fun panel(scenes: List<String>): HowToPanel =
        panels.getOrPut(scenes.joinToString(",")) { howTo(*scenes.toTypedArray()) }.also { it.update() }

    private fun stepTitle(step: SetupStep) = getString(when (step) {
        SetupStep.VOICE -> R.string.step_voice
        SetupStep.RADIO -> R.string.setup_radio_title
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
        SetupStep.RADIO -> getString(R.string.setup_radio_hint)
        SetupStep.HELLO -> getString(R.string.step_hello_hint)
    }

    private fun doneDetail(step: SetupStep) = when (step) {
        SetupStep.VOICE -> GeneratedPackCatalog.entries.firstOrNull { it.id == core.currentLanguage().voicePackId }
            ?.let { getString(R.string.step_voice_done, getString(R.string.setup_voice_title), SetupPlan.megabytes(listOf(it))) }
            ?: getString(R.string.setup_voice_title)
        SetupStep.RADIO -> getString(R.string.radio_connected)
        SetupStep.HELLO -> getString(R.string.step_hello_done)
    }

    private fun stepActions(step: SetupStep, missing: List<CatalogEntry>): View = when (step) {
        SetupStep.VOICE -> voiceActions(missing)
        SetupStep.RADIO -> Ui.button(this, palette, getString(R.string.setup_search), R.drawable.ic_search, filled = true).apply {
            tag = "radio_search"
            setOnClickListener { openRadio() }
        }.let { padded(it) }
        SetupStep.HELLO -> Ui.button(this, palette, getString(R.string.step_hello_button), R.drawable.ic_mic, filled = true).apply {
            tag = "hello_try"
            setOnClickListener { openChat(core.groupChatId()) }
        }.let { padded(it) }
    }

    /** Download progress, or the existing Download and File actions. */
    private fun voiceActions(missing: List<CatalogEntry>): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad() / 2, 0, 0) }
        val full = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val percent = core.voiceDownloadPercent()
        if (percent != null) {
            box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                tag = "voice_bar"
                max = 100
                progress = percent
                progressTintList = ColorStateList.valueOf(palette.text)
            }, full())
            box.addView(Ui.text(this, getString(R.string.progress_percent, percent), palette.text, bold = true).apply { tag = "voice_progress" })
            return box
        }
        if (missing.isNotEmpty()) {
            box.addView(Ui.button(this, palette, getString(R.string.setup_download), R.drawable.ic_download, filled = true).apply {
                tag = "voice_download"
                setOnClickListener { core.downloadVoice(); render() }
            }, full())
        }
        box.addView(Ui.button(this, palette, getString(R.string.setup_file), R.drawable.ic_folder).apply {
            tag = "voice_file"
            setOnClickListener { pickFile() }
        }, full().apply { if (missing.isNotEmpty()) topMargin = pad() / 2 })
        return box
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
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = chatRow(items[position])
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
        row.addView(Looks.avatar(this, palette, chat.kind, 56))
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad() / 2, 0)
        }
        val titleLine = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleLine.addView(Ui.content(Ui.text(this, item.title, palette.text, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        item.peopleHeard?.let { heard ->
            titleLine.addView(Looks.peopleBadge(this, palette).apply {
                tag = "people:${chat.id}"
                Looks.showPeople(this, heard)
            })
        }
        middle.addView(titleLine)
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
            val text = if (prefix != null) getString(R.string.sender_prefix, prefix, last.text) else last.text
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

    /** ➕: the radios the mesh has heard, by name, or "Radio 4a2f" when unnamed. */
    private fun pickPeer() {
        val peers = core.peers()
        if (peers.isEmpty()) { core.note(UiText(R.string.no_radio_heard)); return }
        val names = peers.map { peer -> core.radioLabel(peer.name, peer.id) }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 20f
                    minHeight = Ui.dp(this@ChatsActivity, 56)
                }
        }
        peerDialog?.dismiss()
        peerDialog = AlertDialog.Builder(this).setTitle(R.string.radios_heard)
            .setAdapter(adapter) { _, which -> openChat(core.startDirectChat(peers[which])) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (core.settings.language == null) setIntent(intent) else openFromIntent(intent)
    }

    override fun onDestroy() {
        importDialog?.dismiss() // a pending import is dropped, never installed
        peerDialog?.dismiss()
        super.onDestroy()
    }

    // Test hook
    internal val importDialogForTest: AlertDialog? get() = importDialog?.takeIf { it.isShowing }

    // Test hook
    internal val peerDialogForTest: AlertDialog? get() = peerDialog?.takeIf { it.isShowing }

    private companion object {
        const val STATE_WAITS_FOR_LANGUAGE = "waits_for_language"
        const val STATE_PENDING_IMPORT = "pending_import"
        const val REQUEST_FILE = 101
        const val DONE_MS = 2_000L
    }
}
