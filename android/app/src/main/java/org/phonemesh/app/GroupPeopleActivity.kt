package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Choose people" (decision 72, mockup screen 3): this phone's contacts, each with a tick, and how many people the group
 * would have with you (20 at most). Next names the group ([NameActivity] in group mode); for a group that exists
 * ([EXTRA_CHAT]), it invites the people chosen instead. Only contacts can be chosen: people added in person or by link.
 */
class GroupPeopleActivity : ScreenActivity() {
    private val chosen = LinkedHashSet<String>()
    private lateinit var count: TextView
    private lateinit var next: View
    private lateinit var list: LinearLayout
    private var chatId: Long? = null
    /** People already on the list or invited (adding to a group): shown ticked and fixed. */
    private var already: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT, -1).takeIf { it >= 0 }
        chatId?.let { id -> already = core.groupPeople(id).map { it.account }.toSet() }
        savedInstanceState?.getStringArray(STATE_CHOSEN)?.let { chosen += it }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(), 0, pad(), pad()) }
        column.addView(backBar(getString(if (chatId == null) R.string.group_choose else R.string.group_add_people)))
        column.addView(noteLine())
        column.addView(Ui.text(this, getString(R.string.group_only_added), palette.muted).apply { tag = "group_only_added" })
        count = Ui.text(this, "", palette.text, 20f, bold = true).apply { tag = "group_count" }
        column.addView(count, full().apply { topMargin = pad() })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "group_candidates" }
        column.addView(list, full())
        next = Ui.button(this, palette, getString(if (chatId == null) R.string.group_next else R.string.group_add_people), R.drawable.ic_check, filled = true).apply {
            tag = "group_next"
            setOnClickListener { onNext() }
        }
        column.addView(next, full().apply { topMargin = pad() })
        show(ScrollView(this).apply { addView(column) })
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_CHOSEN, chosen.toTypedArray())
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun total() = (if (chatId == null) 1 else already.size) + chosen.size

    private fun onNext() {
        if (chosen.isEmpty()) return
        val id = chatId
        if (id == null) {
            NameActivity.openForGroup(this, chosen.toList())
            finish()
        } else {
            if (core.addToGroup(id, chosen.toList())) finish()
        }
    }

    override fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        for (contact in core.groupCandidates()) {
            val fixed = contact.accountId in already
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(this@GroupPeopleActivity, 64)
                tag = "candidate:${contact.accountId}"
                isClickable = !fixed
            }
            row.addView(Looks.avatar(this, palette, ChatKind.PERSON, 48, core.faceOf(contact)))
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(Ui.text(this, contact.name, palette.text, bold = true))
            if (!contact.met) words.addView(Ui.text(this, getString(R.string.not_met), palette.muted))
            row.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
            val tick = CheckBox(this).apply {
                isChecked = fixed || contact.accountId in chosen
                isEnabled = !fixed
                contentDescription = contact.name
                minimumWidth = Ui.dp(this@GroupPeopleActivity, 48)
                minimumHeight = Ui.dp(this@GroupPeopleActivity, 48)
                setOnClickListener { toggle(contact.accountId) }
            }
            row.addView(tick)
            if (!fixed) row.setOnClickListener { toggle(contact.accountId) }
            list.addView(row, full())
        }
        count.text = getString(R.string.group_count, total())
        Ui.enable(next, chosen.isNotEmpty() && total() <= MAX)
    }

    private fun toggle(account: String) {
        if (account in chosen) chosen -= account
        else if (total() < MAX) chosen += account
        else core.note(UiText(R.string.group_full))
        render()
    }

    companion object {
        private const val EXTRA_CHAT = "chat"
        private const val STATE_CHOSEN = "chosen"
        const val MAX = 20

        /** Choose people for a new group ([chatId] null), or more people for the group in [chatId]. */
        fun open(context: Context, chatId: Long?) =
            context.startActivity(Intent(context, GroupPeopleActivity::class.java).apply { chatId?.let { putExtra(EXTRA_CHAT, it) } })
    }
}
