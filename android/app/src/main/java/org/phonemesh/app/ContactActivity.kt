package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.phonemesh.core.PackKind
import org.phonemesh.core.account.AccountId

/** A person's local details: safety comparison and a listening voice, never an identity edit. */
class ContactActivity : ScreenActivity() {
    private var chatId = 0L
    private lateinit var name: TextView
    private lateinit var met: TextView
    private lateinit var choices: LinearLayout
    private lateinit var restart: LinearLayout
    private lateinit var missing: TextView
    private lateinit var feedback: TextView
    private var choiceSignature: List<Pair<String?, String>>? = null
    private val buttons = LinkedHashMap<String?, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT, -1L)
        val chat = core.history.chat(chatId)
        if (chat?.kind != ChatKind.PERSON || core.history.contactRow(chat.address) == null) { finish(); return }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.contact_details)))
        column.addView(Looks.avatar(this, palette, ChatKind.PERSON, 48, core.chatColour(chat)))
        name = Ui.content(Ui.text(this, "", palette.text, 24f, bold = true)).apply { tag = "contact_name" }
        column.addView(name)
        met = Ui.text(this, "", palette.muted).apply { tag = "contact_met" }
        column.addView(met)
        column.addView(Ui.button(this, palette, getString(R.string.safety_compare), R.drawable.ic_check).apply {
            tag = "contact_safety"
            setOnClickListener {
                core.history.contactRow(chat.address)?.let { row ->
                    SafetyActivity.open(this@ContactActivity, AccountId.of(Accounts.identityOf(row.card)))
                }
            }
        }, full())
        restart = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@ContactActivity, getString(R.string.contact_start_again_note), palette.muted))
            addView(Ui.button(this@ContactActivity, palette, getString(R.string.contact_start_again), R.drawable.ic_camera).apply {
                tag = "contact_start_again"
                setOnClickListener { AddSomeoneActivity.open(this@ContactActivity) }
            }, full())
        }
        column.addView(restart, full())
        column.addView(Ui.text(this, getString(R.string.contact_voice), palette.text, bold = true), full())
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(choices)
        missing = Ui.text(this, getString(R.string.contact_voice_missing), palette.muted).apply { tag = "contact_voice_missing" }
        column.addView(missing, full())
        feedback = Ui.text(this, "", palette.text).apply {
            tag = "contact_voice_feedback"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(feedback, full())
        show(ScrollView(this).apply { addView(column) })
        render()
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = pad() / 2 }

    override fun render() {
        if (!::name.isInitialized) return
        val chat = core.history.chat(chatId)
        val row = chat?.takeIf { it.kind == ChatKind.PERSON }?.let { core.history.contactRow(it.address) }
            ?: run { finish(); return }
        name.text = row.name

        met.setText(if (row.met) R.string.safety_met else R.string.not_met)
        restart.visibility = if (core.history.outbox(row.accountId).any { it.state == "FAILED" }) View.VISIBLE else View.GONE
        val options: List<Pair<String?, String>> = listOf(null to getString(R.string.contact_voice_auto)) +
            core.store.list().filter { it.kind == PackKind.VOICE }.map { it.id to core.text(core.packName(it)) }
        if (options != choiceSignature) {
            choiceSignature = options
            choices.removeAllViews()
            buttons.clear()
            options.forEach { (id, label) ->
                val button = Ui.button(this, palette, label, null).apply {
                    tag = "contact_voice:${id ?: "auto"}"
                    if (id != null) Ui.content(this)
                    setOnClickListener {
                        val saved = core.setContactVoice(row.accountId, id)
                        feedback.setText(if (saved) R.string.contact_voice_saved else R.string.contact_voice_failed)
                        feedback.setTextColor(if (saved) palette.text else palette.bad)
                        render()
                    }
                }
                buttons[id] = button
                choices.addView(button, full())
            }
        }
        options.forEach { (id, label) ->
            buttons.getValue(id).apply {
                isSelected = row.voice == id
                setCompoundDrawablesRelativeWithIntrinsicBounds(if (isSelected) R.drawable.ic_check else 0, 0, 0, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(palette.text)
                contentDescription = if (isSelected) getString(R.string.contact_voice_selected, label) else label
            }
        }
        missing.visibility = if (row.voice != null && row.voice !in buttons) View.VISIBLE else View.GONE
    }

    companion object {
        private const val EXTRA_CHAT = "chat"
        fun intent(context: Context, chatId: Long): Intent = Intent(context, ContactActivity::class.java).putExtra(EXTRA_CHAT, chatId)
    }
}
