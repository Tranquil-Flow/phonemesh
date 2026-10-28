package org.phonemesh.app

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * "Group details" (decision 72, mockup screen 5): the group's name and picture, its people (admins marked, people not
 * joined yet, people who are not contacts), the group code in four pictures to compare, the "New members see recent
 * messages" choice (decision 74), and Leave group. Admins can add, remove and make admins; the last admin leaving
 * chooses who becomes admin.
 */
class GroupDetailsActivity : ScreenActivity() {
    private var chatId = 0L
    private lateinit var head: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var adminArea: LinearLayout
    private lateinit var code: LinearLayout
    private lateinit var recent: Switch
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT, -1L)
        if (core.groupView(chatId) == null) { finish(); return }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(), 0, pad(), pad()) }
        column.addView(backBar(getString(R.string.group_details)))
        column.addView(noteLine())
        head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "group_head" }
        column.addView(head, full())
        adminArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(adminArea, full())
        column.addView(Ui.text(this, getString(R.string.group_members), palette.text, bold = true).apply { setPadding(0, pad(), 0, 0) }, full())
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "group_people" }
        column.addView(list, full())
        column.addView(Ui.text(this, getString(R.string.group_code), palette.text, bold = true).apply { setPadding(0, pad(), 0, 0) }, full())
        column.addView(Ui.text(this, getString(R.string.group_same_pictures), palette.muted), full())
        code = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "group_code"; gravity = Gravity.CENTER_VERTICAL }
        column.addView(code, full())
        recent = Switch(this).apply {
            tag = "group_recent"
            text = getString(R.string.group_history)
            textSize = Ui.BODY_SP
            setTextColor(palette.text)
            minHeight = Ui.dp(this@GroupDetailsActivity, 48)
            setOnClickListener { core.setGroupRecent(chatId, isChecked); render() }
        }
        column.addView(recent, full().apply { topMargin = pad() })
        column.addView(Ui.button(this, palette, getString(R.string.group_leave), R.drawable.ic_close).apply {
            tag = "group_leave"
            setOnClickListener { askToLeave() }
        }, full().apply { topMargin = pad() * 2 })
        show(ScrollView(this).apply { addView(column) })
        render()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    override fun render() {
        if (!::recent.isInitialized) return
        val group = core.groupView(chatId) ?: run { finish(); return }
        head.removeAllViews()
        head.addView(Looks.avatar(this, palette, ChatKind.PRIVATE_GROUP, 64, group.face))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Ui.content(Ui.text(this, group.name, palette.text, 24f, bold = true)).apply { tag = "group_name" })
        words.addView(Ui.text(this, getString(R.string.group_people, group.people), palette.muted))
        head.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })

        adminArea.removeAllViews()
        val canChange = group.admin && !group.gone && !group.conflict
        if (canChange) {
            adminArea.addView(Ui.button(this, palette, getString(R.string.group_name_picture), R.drawable.ic_person).apply {
                tag = "group_restyle"
                setOnClickListener { NameActivity.openForGroupChat(this@GroupDetailsActivity, chatId) }
            }, full().apply { topMargin = pad() / 2 })
            adminArea.addView(Ui.button(this, palette, getString(R.string.group_add_people), R.drawable.ic_plus).apply {
                tag = "group_add"
                setOnClickListener { GroupPeopleActivity.open(this@GroupDetailsActivity, chatId) }
            }, full().apply { topMargin = pad() / 2 })
        }

        list.removeAllViews()
        for (person in core.groupPeople(chatId)) list.addView(personRow(person, canChange), full())

        code.removeAllViews()
        group.code?.forEach { icon ->
            code.addView(ImageView(this).apply {
                setImageResource(SafetyIcons.drawables[icon])
                contentDescription = getString(SafetyIcons.names[icon])
                setPadding(pad() / 4, pad() / 4, pad() / 4, pad() / 4)
            }, LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)))
        }
        code.visibility = if (group.code != null) View.VISIBLE else View.GONE

        recent.isChecked = group.recentForNew
        recent.visibility = if (group.admin && !group.gone) View.VISIBLE else View.GONE
        Ui.enable(recent, canChange)
    }

    /** One person: picture, name ("(you)"), Admin or Not joined yet, Not in your contacts; an admin taps for choices. */
    private fun personRow(person: AppCore.GroupPerson, canChange: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(this@GroupDetailsActivity, 64)
        tag = "person:${person.account}"
        addView(Looks.avatar(this@GroupDetailsActivity, palette, ChatKind.PERSON, 48, person.face))
        val words = LinearLayout(this@GroupDetailsActivity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Ui.text(this@GroupDetailsActivity, if (person.me) getString(R.string.group_you, person.name) else person.name, palette.text, bold = true))
        val notes = listOfNotNull(
            getString(R.string.group_admin).takeIf { person.admin },
            getString(R.string.group_not_joined).takeIf { !person.joined },
            getString(R.string.group_not_contact).takeIf { !person.contact && !person.me },
        )
        if (notes.isNotEmpty()) words.addView(Ui.text(this@GroupDetailsActivity, notes.joinToString(" · "), palette.muted))
        addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        if (!person.me && (canChange && person.joined || !person.contact)) {
            isClickable = true
            isFocusable = true
            background = Ui.ripple(android.graphics.drawable.ColorDrawable(palette.background), Ui.pressedOf(palette.text))
            setOnClickListener { personActions(person, canChange) }
        }
    }

    private fun personActions(person: AppCore.GroupPerson, canChange: Boolean) {
        val choices = ArrayList<Pair<String, () -> Unit>>()
        if (canChange && !person.admin) choices += getString(R.string.group_make_admin) to { core.makeGroupAdmin(chatId, person.account); Unit }
        if (!person.contact) choices += getString(R.string.group_add_contact) to {
            core.note(UiText(R.string.group_need_contacts, listOf(person.name))); AddSomeoneActivity.open(this)
        }
        if (canChange) choices += getString(R.string.group_remove) to { askToRemove(person) }
        if (choices.isEmpty()) return
        dialog?.dismiss()
        dialog = Actions.readable(AlertDialog.Builder(this).setTitle(person.name)
            .setItems(choices.map { it.first }.toTypedArray()) { _, i -> choices[i].second(); render() }
            .setNegativeButton(R.string.close, null).create()).apply { show() }
    }

    private fun askToRemove(person: AppCore.GroupPerson) {
        dialog?.dismiss()
        dialog = Actions.readable(AlertDialog.Builder(this).setTitle(getString(R.string.group_remove))
            .setMessage(person.name + "\n" + getString(R.string.group_remove_note))
            .setPositiveButton(R.string.group_remove) { _, _ -> core.removeFromGroup(chatId, person.account); render() }
            .setNegativeButton(R.string.close, null).create()).apply { show() }
    }

    /** Leave group; the last admin first chooses who becomes admin (decision 72 d). */
    private fun askToLeave() {
        val group = core.groupView(chatId) ?: return
        val people = core.groupPeople(chatId)
        val lastAdmin = group.admin && people.none { it.admin && !it.me && it.joined }
        val heirs = people.filter { !it.me && it.joined }
        dialog?.dismiss()
        val builder = AlertDialog.Builder(this).setTitle(R.string.group_leave_q)
        if (lastAdmin && heirs.isNotEmpty()) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(), pad() / 2, pad(), 0) }
            box.addView(Ui.text(this, getString(R.string.group_leave_note), palette.muted))
            box.addView(Ui.text(this, getString(R.string.group_last_admin), palette.text, bold = true))
            val group2 = android.widget.RadioGroup(this).apply { tag = "group_heirs" }
            heirs.forEachIndexed { i, h ->
                group2.addView(android.widget.RadioButton(this).apply {
                    id = View.generateViewId(); text = h.name; textSize = Ui.BODY_SP; setTextColor(palette.text); minHeight = Ui.dp(this@GroupDetailsActivity, 48)
                    tag = "heir:${h.account}"
                    isChecked = i == 0
                })
            }
            box.addView(group2)
            builder.setView(box).setPositiveButton(R.string.group_leave) { _, _ ->
                val chosen = (0 until group2.childCount).map { group2.getChildAt(it) as android.widget.RadioButton }.indexOfFirst { it.isChecked }
                if (core.leaveGroup(chatId, heirs.getOrNull(chosen)?.account)) finish()
            }
        } else {
            builder.setMessage(R.string.group_leave_note).setPositiveButton(R.string.group_leave) { _, _ -> if (core.leaveGroup(chatId, null)) finish() }
        }
        dialog = Actions.readable(builder.setNegativeButton(R.string.close, null).create()).apply { show() }
    }

    companion object {
        private const val EXTRA_CHAT = "chat"
        fun open(context: Context, chatId: Long) = context.startActivity(Intent(context, GroupDetailsActivity::class.java).putExtra(EXTRA_CHAT, chatId))
    }
}
