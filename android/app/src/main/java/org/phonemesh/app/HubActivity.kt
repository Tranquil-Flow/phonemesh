package org.phonemesh.app

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.phonemesh.core.hub.HubCard
import org.phonemesh.core.hub.HubDelivery
import org.phonemesh.core.hub.MailPaths

/**
 * The village hub (accounts step 16): scan or forget its QR, which ways reach it, what failed, and the opt-in to keep
 * it when the app is closed. A connection is never shown as proof of the hub: "linked" is a connection, and only an
 * answer over the pinned key earns "answered" (see [HubWay]).
 */
class HubActivity : ScreenActivity() {
    private lateinit var stateIcon: ImageView
    private lateinit var state: TextView
    private lateinit var name: TextView
    private lateinit var wifi: TextView
    private lateinit var bluetooth: TextView
    private lateinit var sending: TextView
    private lateinit var failure: TextView
    private lateinit var allow: View
    private lateinit var forget: View
    private lateinit var roaming: Switch
    private lateinit var roamingState: TextView
    private var forgetDialog: AlertDialog? = null
    private var rendering = false
    private var askedBluetooth = false
    private var roamingAfterAllow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), 0, pad(), pad())
        }
        column.addView(backBar(getString(R.string.hub_title)))
        column.addView(noteLine())
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@HubActivity, 64)
        }
        stateIcon = Ui.icon(this, R.drawable.ic_radio_off, palette.text, 40)
        head.addView(stateIcon)
        state = Ui.text(this, "", palette.text, 20f, bold = true).apply { tag = "hub_state" }
        head.addView(state, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = pad() })
        column.addView(head)
        name = Ui.content(Ui.text(this, "", palette.muted).apply { tag = "hub_name" })
        column.addView(name)
        wifi = Ui.text(this, "", palette.text).apply { tag = "hub_wifi" }
        column.addView(wifi)
        bluetooth = Ui.text(this, "", palette.text).apply { tag = "hub_bluetooth" }
        column.addView(bluetooth)
        sending = Ui.text(this, "", palette.text, bold = true).apply { tag = "hub_sending" }
        column.addView(sending, spaced())
        failure = Ui.text(this, "", palette.bad, bold = true).apply { tag = "hub_failure" }
        column.addView(failure, spaced())
        column.addView(Ui.button(this, palette, getString(R.string.hub_scan), R.drawable.ic_camera, filled = true).apply {
            tag = "hub_scan"
            setOnClickListener { startActivity(Intent(this@HubActivity, ScanActivity::class.java)) }
        }, spaced())
        allow = Ui.button(this, palette, getString(R.string.hub_allow), R.drawable.ic_radio_on).apply {
            tag = "hub_allow"
            setOnClickListener { askBluetooth(forRoaming = false) }
        }
        column.addView(allow, spaced())
        forget = Ui.button(this, palette, getString(R.string.hub_forget), R.drawable.ic_close).apply {
            tag = "hub_forget"
            setOnClickListener { askForget() }
        }
        column.addView(forget, spaced())
        column.addView(Ui.text(this, getString(R.string.settings_hub_background), palette.text, 20f, bold = true).apply {
            setPadding(0, pad() * 3 / 2, 0, pad() / 2)
        })
        roaming = Switch(this).apply {
            tag = "hub_roaming"
            text = getString(R.string.hub_roaming)
            contentDescription = getString(R.string.hub_roaming)
            textSize = Ui.BODY_SP
            setTextColor(palette.text)
            minHeight = Ui.dp(this@HubActivity, 64)
            setOnCheckedChangeListener { _, on -> if (!rendering) toggleRoaming(on) }
        }
        column.addView(roaming)
        column.addView(Ui.text(this, getString(R.string.hub_roaming_hint), palette.muted))
        roamingState = Ui.text(this, "", palette.text, bold = true).apply { tag = "roaming_state" }
        column.addView(roamingState, spaced())
        show(ScrollView(this).apply { addView(column) })
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = pad() }

    override fun render() {
        val status = core.hubStatus()
        val card = core.settings.hubCard?.let { HubCard.parse(it) }
        val best = maxOf(status.wifi, status.bluetooth)
        val (icon, tint, words) = when {
            !status.saved -> Triple(R.drawable.ic_radio_off, palette.text, R.string.hub_state_none)
            best == HubWay.ANSWERED -> Triple(R.drawable.ic_check, palette.good, R.string.hub_state_answered)
            best == HubWay.LINKED -> Triple(R.drawable.ic_radio_on, palette.text, R.string.hub_state_linked)
            else -> Triple(R.drawable.ic_radio_off, palette.text, R.string.hub_state_searching)
        }
        stateIcon.setImageResource(icon)
        stateIcon.imageTintList = ColorStateList.valueOf(tint)
        state.text = getString(words)
        name.visibility = if (card != null) View.VISIBLE else View.GONE
        name.text = card?.let { getString(R.string.hub_wifi_name, it.wifi) }.orEmpty()
        wifi.visibility = if (status.saved) View.VISIBLE else View.GONE
        bluetooth.visibility = wifi.visibility
        wifi.text = getString(R.string.hub_way_wifi, getString(wayWords(status.wifi)))
        bluetooth.text = getString(R.string.hub_way_bluetooth, getString(wayWords(status.bluetooth)))
        val path = when {
            status.sending == MailPaths.Path.WIFI_HUB -> R.string.hub_path_wifi
            status.sending == MailPaths.Path.BLUETOOTH_HUB -> R.string.hub_path_bluetooth
            core.radioReady() -> R.string.hub_path_radio
            else -> R.string.hub_path_none
        }
        sending.text = getString(R.string.hub_sending, getString(path))
        val failed = status.failure
        failure.visibility = if (failed != null && status.saved) View.VISIBLE else View.GONE
        failure.text = failed?.let { getString(failureWords(it)) }.orEmpty()
        val missing = status.saved && core.access.radioPermissionsMissing().isNotEmpty()
        allow.visibility = if (missing) View.VISIBLE else View.GONE
        forget.visibility = if (status.saved) View.VISIBLE else View.GONE
        // The switch shows the person's choice; the line under it says whether the service really runs.
        rendering = true
        roaming.isChecked = core.settings.hubRoaming
        rendering = false
        val block = core.roamingBlock
        roamingState.text = getString(when {
            core.roamingActive -> R.string.roaming_on
            core.roamingStarting -> R.string.roaming_starting
            block != null -> block.words
            else -> R.string.roaming_off
        })
    }

    private fun wayWords(way: HubWay): Int = when (way) {
        HubWay.NONE -> R.string.hub_way_none
        HubWay.SEARCHING -> R.string.hub_way_searching
        HubWay.LINKED -> R.string.hub_way_linked
        HubWay.ANSWERED -> R.string.hub_way_answered
    }

    private fun failureWords(failed: HubFailure): Int = when (failed.result) {
        HubDelivery.Result.BUSY -> R.string.hub_failure_busy
        HubDelivery.Result.REFUSED -> R.string.hub_failure_refused
        else -> R.string.hub_failure_silent
    }

    private fun toggleRoaming(on: Boolean) {
        // AppCore decides and explains (a note with the remedy); a missing Bluetooth permission is asked for here, once.
        if (!core.setHubRoaming(on) && on && core.roamingBlock == RoamingBlock.PERMISSION) askBluetooth(forRoaming = true)
        render()
    }

    private fun askBluetooth(forRoaming: Boolean) {
        val missing = core.access.radioPermissionsMissing()
        if (missing.isEmpty()) return
        if (askedBluetooth) {
            // Android shows no second prompt after a refusal: say where to allow it.
            core.note(UiText(R.string.hub_bluetooth_refused))
            return
        }
        askedBluetooth = true
        roamingAfterAllow = forRoaming
        requestPermissions(missing.toTypedArray(), REQUEST_BLUETOOTH)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        val again = roamingAfterAllow
        roamingAfterAllow = false
        if (grantResults.isEmpty() || !grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            core.note(UiText(R.string.hub_bluetooth_refused))
        } else {
            core.retryHub()
            if (again) core.setHubRoaming(true)
        }
        render()
    }

    private fun askForget() {
        forgetDialog = Actions.readable(AlertDialog.Builder(this).setTitle(R.string.hub_forget_title)
            .setMessage(R.string.hub_forget_ask)
            .setPositiveButton(R.string.hub_forget) { _, _ ->
                core.forgetHub()
                core.note(UiText(R.string.hub_forgotten))
            }
            .setNegativeButton(R.string.close, null)
            .create()).also { dialog ->
                dialog.show()
                dialog.findViewById<TextView>(android.R.id.message)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.BODY_SP)
            }
    }

    override fun onDestroy() {
        forgetDialog?.dismiss()
        super.onDestroy()
    }

    // Test hook
    internal val forgetDialogForTest: AlertDialog? get() = forgetDialog?.takeIf { it.isShowing }

    companion object {
        private const val REQUEST_BLUETOOTH = 61
    }
}
