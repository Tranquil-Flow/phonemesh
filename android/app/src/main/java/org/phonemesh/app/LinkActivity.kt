package org.phonemesh.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.phonemesh.core.account.AddCode

/**
 * Opens an add-me link (plan step 11): `https://tranquil-flow.github.io/phonemesh/add#<card>` straight from Android
 * (App Link), or the link page's button, which hands the part after `#` as the extra `card`. Adds the person, sends
 * the hello and opens their safety picture (their chat comes with step 12); anything else goes to the chats with a note. Shows nothing itself.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = AppCore.get(this)
        val text = intent?.getStringExtra("card")?.let { AddCode.parseFragment(it) }
            ?: intent?.dataString?.let { AddCode.parse(it) }
        var next: Intent? = null
        var notice: Int? = null
        when {
            text == null -> notice = R.string.add_bad_code
            core.account() == null -> notice = R.string.add_name_first
            else -> when (val added = core.accountsFlow.openLink(text.card)) {
                is Accounts.Added.Contact -> next = SafetyActivity.intent(this, added.account)
                Accounts.Added.Own -> notice = R.string.add_own_code
                Accounts.Added.NotSaved -> notice = R.string.mail_save_failed
                else -> notice = R.string.add_bad_code
            }
        }
        // Opened from outside the app: the chats go underneath, so Back from the safety picture lands there.
        val chats = if (isTaskRoot || notice != null) Intent(this, ChatsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { notice?.let { putExtra(EXTRA_NOTICE, it) } } else null
        val stack = listOfNotNull(chats, next)
        if (stack.isNotEmpty()) startActivities(stack.toTypedArray())
        finish()
    }

    companion object { const val EXTRA_NOTICE = "phonemesh_link_notice" }
}
