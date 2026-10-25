package org.phonemesh.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import org.phonemesh.core.account.AccountId
import kotlin.concurrent.thread

/**
 * "Les mêmes six images ?" (plan step 11; picture same_picture): the six icons this phone and the contact's both show.
 * "Ce sont les mêmes" marks the contact met in person; "Pas les mêmes" warns and keeps it not met (spec decisions 21-23).
 */
class SafetyActivity : ScreenActivity() {
    private lateinit var account: AccountId
    private lateinit var grid: GridLayout
    private lateinit var same: android.view.View
    private lateinit var pictureStatus: android.widget.TextView
    private var pictureReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        account = AccountId.fromBytes(intent.getByteArrayExtra(EXTRA_ACCOUNT)!!)
        val contact = core.history.contactRow(account.toString())
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad(), 0, pad(), pad()) }
        column.addView(backBar(contact?.name))
        column.addView(noteLine())
        column.addView(Ui.text(this, getString(R.string.safety_compare), palette.text, 20f, bold = true))
        pictureStatus = Ui.text(this, getString(R.string.safety_loading), palette.text).apply { tag = "safety_status" }
        column.addView(pictureStatus)
        grid = GridLayout(this).apply { columnCount = 3; tag = "safety_icons" }
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad() })
        same = Ui.button(this, palette, getString(R.string.safety_same), R.drawable.ic_check, filled = true).apply {
            tag = "safety_same"
            isEnabled = false
            setOnClickListener {
                if (pictureReady && grid.childCount == 6) {
                    core.setContactMet(account, true)
                    core.note(UiText(R.string.safety_met))
                    finish()
                }
            }
        }
        column.addView(same, full())
        column.addView(Ui.button(this, palette, getString(R.string.safety_differ), R.drawable.ic_close).apply {
            tag = "safety_differ"
            setTextColor(palette.bad)
            background = Ui.outline(this@SafetyActivity, palette.background, palette.bad)
            // Keep the comparison open so the warning is visible, not lost behind a finishing screen.
            setOnClickListener { core.setContactMet(account, false); core.note(UiText(R.string.safety_differ_note)) }
        }, full())
        show(ScrollView(this).apply { addView(column) })
        // A slow hash (65,536 rounds): off the main thread.
        val flow = core.accountsFlow
        val compute = computePicture
        thread(name = "safety-picture") {
            val icons = try { compute(flow, account) } catch (_: Exception) { null }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (icons == null || icons.size != 6 || icons.any { it !in SafetyIcons.drawables.indices || it !in SafetyIcons.names.indices }) {
                    pictureFailed()
                } else {
                    try { fill(icons) } catch (_: Exception) { pictureFailed() }
                }
            }
        }
    }

    private fun full() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = pad() }

    private fun pictureFailed() {
        pictureReady = false
        same.isEnabled = false
        grid.removeAllViews()
        pictureStatus.setText(R.string.safety_failed)
        pictureStatus.setTextColor(palette.bad)
        pictureStatus.visibility = android.view.View.VISIBLE
    }

    private fun fill(icons: IntArray) {
        if (isDestroyed || isFinishing) return
        val names = icons.map { getString(SafetyIcons.names[it]) }
        icons.forEachIndexed { i, icon ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                tag = "safety_icon:$icon"
                contentDescription = names[i]
                addView(android.widget.ImageView(this@SafetyActivity).apply { setImageResource(SafetyIcons.drawables[icon]) },
                    LinearLayout.LayoutParams(Ui.dp(this@SafetyActivity, 80), Ui.dp(this@SafetyActivity, 80)))
                addView(Ui.text(this@SafetyActivity, names[i], palette.text, bold = true).apply { gravity = Gravity.CENTER })
            }
            grid.addView(cell, GridLayout.LayoutParams(GridLayout.spec(i / 3, 1f), GridLayout.spec(i % 3, 1f)).apply { width = 0 })
        }
        // All six image resources and labels have been installed before confirmation becomes possible.
        pictureReady = grid.childCount == 6
        same.isEnabled = pictureReady
        pictureStatus.visibility = android.view.View.GONE
    }

    override fun render() = Unit

    companion object {
        // Tests hold the real worker before delegating to Accounts.safetyPicture.
        internal var computePicture: (Accounts, AccountId) -> IntArray? = { flow, id -> flow.safetyPicture(id) }
        const val EXTRA_ACCOUNT = "account"
        fun intent(context: Context, account: AccountId): Intent =
            Intent(context, SafetyActivity::class.java).putExtra(EXTRA_ACCOUNT, account.toBytes())
        fun open(context: Context, account: AccountId) = context.startActivity(intent(context, account))
    }
}
