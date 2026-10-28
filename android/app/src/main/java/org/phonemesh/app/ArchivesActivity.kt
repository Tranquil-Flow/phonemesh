package org.phonemesh.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.graphics.Bitmap
import android.widget.ProgressBar
import org.phonemesh.core.backup.HistoryBackup
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Saved chats (backup spec 5.7; v0.1.2 B3): every saved copy read back on this phone, each marked "from this saved
 * copy", with its chats listed apart from live chats. These are history only: opening one shows its messages, nothing
 * more (see [ArchiveChatActivity]). Removing a copy takes it off this phone; the file it came from stays where it is.
 */
class ArchivesActivity : ScreenActivity() {
    private lateinit var body: LinearLayout
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "archives").apply { isDaemon = true } }

    /** The confirmation of the last Remove, for tests (as ChatsActivity keeps its import dialog). */
    internal var removeDialogForTest: android.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.archives_title)))
        column.addView(noteLine())
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "archives_body" }
        column.addView(body)
        show(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** What a copy shows (its chats, counted over every message) is asked on a worker, with a bar: never on the screen's thread (B4-4). */
    private fun load(failure: Int? = null) {
        body.removeAllViews()
        body.addView(ProgressBar(this).apply { isIndeterminate = true; tag = "archives_progress" })
        val history = core.history
        worker.execute {
            val data = runCatching { ArchiveRows(history).let { rows -> rows.archives().map { it to rows.chats(it.archiveId) } } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                body.removeAllViews()
                if (failure != null) line(getString(failure), bold = true, tag = "archives_failure")
                data.fold({ build(it) }) { line(getString(R.string.archives_failed), bold = true, tag = "archives_failed") }
            }
        }
    }

    /** Takes the copy off on a worker (thousands of rows, then its pictures), then shows the list again. */
    private fun remove(archive: ArchiveRows.ArchiveRow) {
        body.removeAllViews()
        body.addView(ProgressBar(this).apply { isIndeterminate = true; tag = "archives_progress" })
        line(getString(R.string.archives_removing), bold = true)
        val history = core.history
        val folder = core.archivesDir
        worker.execute {
            val result = runCatching { ArchiveRows(history).delete(archive.archiveId, folder) }
            runOnUiThread { if (!isFinishing && !isDestroyed) load(if (result.isFailure) R.string.archives_remove_failed else null) }
        }
    }

    /** Saved copies do not change with the radio or the mail: nothing to redraw. */
    override fun render() = Unit

    private fun line(text: String, bold: Boolean = false, tag: String? = null) =
        body.addView(Ui.content(Ui.text(this, text, if (bold) palette.text else palette.muted, bold = bold)).apply {
            setPadding(0, pad() / 4, 0, pad() / 4)
            if (tag != null) this.tag = tag
        })

    private fun build(loaded: List<Pair<ArchiveRows.ArchiveRow, List<ArchiveRows.ChatRow>>>) {
        val archives = loaded.map { it.first }
        if (archives.isEmpty()) { line(getString(R.string.archives_empty), bold = true, tag = "archives_empty"); return }
        for ((archive, chats) in loaded) {
            val hex = archive.hex
            line(getString(R.string.archives_from), bold = true, tag = "archive_from:$hex")
            line(archive.ownName)
            line(getString(R.string.archives_made, Ui.date(this, archive.created, "dMMMMyyyy")))
            line(getString(R.string.archives_summary, archive.chats, archive.messages))
            line(if (archive.scopeStart == 0L) getString(R.string.archives_all)
                else getString(R.string.archives_range, Ui.date(this, archive.scopeStart, "dMMMyyyy"), Ui.date(this, archive.scopeEnd, "dMMMyyyy")))
            if (archive.missingPictures > 0) line(getString(R.string.backup_missing_pictures, archive.missingPictures))
            val folder = File(core.archivesDir, hex)
            if (archive.pictures > archive.missingPictures && !folder.isDirectory) line(getString(R.string.archives_storage), bold = true, tag = "archive_storage:$hex")
            for (chat in chats.take(SHOWN)) {
                val kind = when (chat.kind) {
                    HistoryBackup.GROUP_CHAT -> getString(R.string.backup_kind_group) + " · "
                    HistoryBackup.SHARED_CHAT -> getString(R.string.backup_kind_shared) + " · "
                    HistoryBackup.LEGACY_CHAT -> getString(R.string.backup_kind_old) + " · "
                    else -> ""
                }
                val label = chat.title.ifEmpty { getString(R.string.backup_kind_shared) } + " · " + kind + getString(R.string.backup_chat_count, chat.messages)
                body.addView(Ui.button(this, palette, label, R.drawable.ic_person).apply {
                    tag = "archive_chat:$hex:${chat.id}"
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener { ArchiveChatActivity.open(this@ArchivesActivity, hex, chat.id) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 4 })
            }
            if (chats.size > SHOWN) line(getString(R.string.recovery_more, chats.size - SHOWN))
            body.addView(Ui.button(this, palette, getString(R.string.archives_remove), R.drawable.ic_close).apply {
                tag = "archive_remove:$hex"
                setOnClickListener {
                    // The words are set in the dialog's own view, at the app's text size (a dialog's message is drawn small).
                    val words = LinearLayout(this@ArchivesActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(this@ArchivesActivity, getString(R.string.archives_remove_text), palette.text).apply { setPadding(pad(), pad() / 2, pad(), 0) })
                        addView(Ui.text(this@ArchivesActivity, getString(R.string.archives_remove_people), palette.text).apply { setPadding(pad(), pad() / 2, pad(), 0) })
                    }
                    removeDialogForTest = Actions.readable(android.app.AlertDialog.Builder(this@ArchivesActivity)
                        .setTitle(R.string.archives_remove_title).setView(words)
                        .setPositiveButton(R.string.archives_remove_yes) { _, _ -> remove(archive) }
                        .setNegativeButton(R.string.cancel, null).create()).also { it.show() }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() / 2; bottomMargin = pad() })
        }
    }

    companion object {
        private const val SHOWN = 50
        fun open(activity: Activity) = activity.startActivity(Intent(activity, ArchivesActivity::class.java))
    }
}

/**
 * One chat of a saved copy, read only: who said what and when, the picture if the copy holds it, reactions, and the
 * status the message had when it was saved (never a new delivery state). It has no way to send, reply, react, speak,
 * notify or receipt: it only reads the archive tables.
 */
class ArchiveChatActivity : ScreenActivity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var archive: ByteArray
    private lateinit var titleView: android.widget.TextView
    private var chatId = 0L
    private var oldest = Long.MAX_VALUE
    private var names: Map<Long, String> = emptyMap()
    private var loading = false
    private var firstPage = true
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "archive-chat").apply { isDaemon = true } }

    /** One page, read and its pictures decoded on the worker (B4-4): the messages, the reactions to just these, the bitmaps. */
    private class Loaded(
        val title: String?, val names: Map<Long, String>, val page: List<ArchiveRows.MessageRow>,
        val reactions: Map<Long, List<ArchiveRows.ReactionRow>>, val bitmaps: Map<Long, Bitmap?>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archive = ArchiveRows.unhex(intent.getStringExtra(ARCHIVE).orEmpty())
        chatId = intent.getLongExtra(CHAT, 0L)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        val bar = backBar(getString(R.string.archives_title))
        column.addView(bar)
        titleView = (bar as LinearLayout).getChildAt(1) as android.widget.TextView
        column.addView(Ui.content(Ui.text(this, getString(R.string.archives_banner), palette.text, bold = true)).apply { tag = "archive_banner"; setPadding(0, 0, 0, pad() / 2) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "archive_messages" }
        column.addView(list)
        scroll = ScrollView(this).apply { addView(column) }
        show(scroll)
        more()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun render() = Unit

    private fun more() {
        if (loading) return
        loading = true
        list.findViewWithTag<View>("archive_older")?.let(list::removeView)
        val bar = ProgressBar(this).apply { isIndeterminate = true; tag = "archive_progress" }
        list.addView(bar, 0)
        val before = oldest
        val first = firstPage
        val history = core.history
        val folder = File(core.archivesDir, ArchiveRows.hex(archive))
        worker.execute {
            val result = runCatching {
                val rows = ArchiveRows(history)
                val page = rows.messages(archive, chatId, PAGE, before)
                val reactions = rows.reactions(archive, page.map { it.id }).groupBy { it.targetId }
                val bitmaps = HashMap<Long, Bitmap?>()
                for (m in page) if (m.pictureId != 0L) bitmaps[m.id] = rows.picture(archive, m.pictureId)?.file?.let { File(folder, it) }?.takeIf { it.isFile }?.let { decode(it) }
                Loaded(if (first) rows.chat(archive, chatId)?.title?.ifEmpty { null } else null, if (first) rows.contacts(archive).associate { it.id to it.name } else emptyMap(), page, reactions, bitmaps)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                list.removeView(bar)
                loading = false
                result.fold({ show(it, first) }) { list.addView(Ui.content(Ui.text(this, getString(R.string.archives_failed), palette.text, bold = true)).apply { tag = "archive_failed" }, 0) }
            }
        }
    }

    private fun show(loaded: Loaded, first: Boolean) {
        if (first) {
            firstPage = false
            names = loaded.names
            loaded.title?.let { titleView.text = it }
        }
        val page = loaded.page
        if (page.isEmpty() && list.childCount == 0) {
            list.addView(Ui.content(Ui.text(this, getString(R.string.archives_empty_chat), palette.muted)).apply { tag = "archive_empty_chat" })
            return
        }
        var insert = 0
        if (page.size == PAGE) {
            list.addView(Ui.button(this, palette, getString(R.string.archives_older), null).apply {
                tag = "archive_older"
                setOnClickListener { more() }
            }, insert++)
        }
        for (m in page) list.addView(bubble(m, loaded.reactions[m.id].orEmpty(), loaded.bitmaps[m.id]), insert++, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad() / 2; gravity = if (m.outgoing) Gravity.END else Gravity.START })
        if (page.isNotEmpty()) oldest = page.first().id
        if (first) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: ArchiveRows.MessageRow, reactions: List<ArchiveRows.ReactionRow>, bitmap: Bitmap?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "archive_message:${m.id}"
            background = Ui.outline(this@ArchiveChatActivity, palette.surface, palette.outline)
            setPadding(pad(), pad() / 2, pad(), pad() / 2)
        }
        val author = m.authorLabel ?: names[m.authorContactId]
        if (!m.outgoing && author != null) box.addView(Ui.content(Ui.text(this, author, palette.text, bold = true)))
        if (m.replyMissing) box.addView(Ui.content(Ui.text(this, getString(R.string.archives_missing_reply), palette.muted)))
        if (m.pictureId != 0L) {
            if (bitmap != null) {
                box.addView(ImageView(this).apply {
                    setImageBitmap(bitmap)
                    contentDescription = getString(R.string.archives_banner)
                    tag = "archive_picture:${m.id}"
                    adjustViewBounds = true
                    maxHeight = Ui.dp(this@ArchiveChatActivity, 240)
                })
            } else box.addView(Ui.content(Ui.text(this, getString(R.string.archives_missing_picture), palette.muted)).apply { tag = "archive_picture_missing:${m.id}" })
        }
        if (m.text.isNotEmpty()) box.addView(Ui.content(Ui.text(this, m.text, palette.text)))
        if (reactions.isNotEmpty()) box.addView(Ui.content(Ui.text(this, reactions.joinToString(" ") { it.emoji }, palette.text)))
        val status = if (m.outgoing) statusText(m.status)?.let { getString(R.string.archives_status, it) } else null
        val time = if (m.time > 0) Ui.date(this, m.time, "dMMMHHmm") else ""
        val caption = listOfNotNull(time.takeIf { it.isNotEmpty() }, status).joinToString(" · ")
        if (caption.isNotEmpty()) box.addView(Ui.content(Ui.text(this, caption, palette.muted)))
        return box
    }

    private fun statusText(status: Int): String? = when (status) {
        1 -> getString(R.string.archives_status_1)
        2 -> getString(R.string.archives_status_2)
        3 -> getString(R.string.archives_status_3)
        4 -> getString(R.string.archives_status_4)
        5 -> getString(R.string.archives_status_5)
        6 -> getString(R.string.archives_status_6)
        else -> null
    }

    /** A saved picture, decoded no larger than the screen needs; an image that does not decode is a placeholder. */
    private fun decode(file: File): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
        return BitmapFactory.decodeFile(file.path)
    }

    companion object {
        private const val ARCHIVE = "archive"
        private const val CHAT = "chat"
        private const val PAGE = 200

        fun intent(context: android.content.Context, archiveHex: String, chatId: Long): Intent =
            Intent(context, ArchiveChatActivity::class.java).putExtra(ARCHIVE, archiveHex).putExtra(CHAT, chatId)

        fun open(activity: Activity, archiveHex: String, chatId: Long) = activity.startActivity(intent(activity, archiveHex, chatId))
    }
}
