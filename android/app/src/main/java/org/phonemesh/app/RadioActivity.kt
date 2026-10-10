package org.phonemesh.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import org.phonemesh.core.SignalBars

/** Nearby radios as large rows with signal bars; tap to connect. Android's pairing prompt may appear. */
class RadioActivity : ScreenActivity() {
    private lateinit var state: TextView
    private lateinit var disconnect: Button
    private lateinit var searching: LinearLayout
    private lateinit var searchAgain: Button
    private var pairPicture: HowToPanel? = null
    private val adapter = RadioAdapter()
    /** Ready reports seen when a radio was tapped; null when none was. Only a later report ends the screen. */
    private var readyAtTap: Int? = null
    private var askedPermissions = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(), pad(), pad(), pad())
        }
        root.addView(backBar(getString(R.string.radio_title)))
        root.addView(noteLine())
        state = Ui.text(this, "", palette.text, bold = true).apply { tag = "radio_state" }
        root.addView(state)
        disconnect = Ui.button(this, palette, getString(R.string.disconnect), R.drawable.ic_close).apply {
            tag = "radio_disconnect"
            setOnClickListener { core.disconnect() }
        }
        root.addView(disconnect)
        searching = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = "searching"
            minimumHeight = Ui.dp(this@RadioActivity, 56)
            addView(ProgressBar(this@RadioActivity).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.text)
            },
                LinearLayout.LayoutParams(Ui.dp(this@RadioActivity, 32), Ui.dp(this@RadioActivity, 32)))
            addView(Ui.text(this@RadioActivity, getString(R.string.radio_searching), palette.text, bold = true).apply {
                setPadding(pad() / 2, 0, 0, 0)
            })
        }
        root.addView(searching)
        if (HowTo.showPair) {
            pairPicture = howTo("pair", compact = true).apply { visibility = View.GONE }
            root.addView(pairPicture)
        }
        searchAgain = Ui.button(this, palette, getString(R.string.setup_search), R.drawable.ic_search).apply {
            tag = "radio_search_again"
            setOnClickListener { beginSearch() }
        }
        root.addView(searchAgain)
        val list = ListView(this).apply {
            tag = "radios"
            divider = null
            adapter = this@RadioActivity.adapter
            setOnItemClickListener { _, _, position, _ -> pick(this@RadioActivity.adapter.getItem(position)) }
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Ui.text(this, getString(R.string.radio_help), palette.muted).apply {
            tag = "radio_help"
            setPadding(0, pad(), 0, 0)
        })
        show(root)
    }

    override fun onScreenStart() = beginSearch()

    override fun onScreenStop() = core.stopScan()

    // The enable request needs BLUETOOTH_CONNECT; DeviceAccess.radioPermissionsMissing() is checked first.
    @SuppressLint("MissingPermission")
    private fun beginSearch() {
        val missing = core.access.radioPermissionsMissing()
        if (missing.isNotEmpty()) {
            if (askedPermissions) core.note(UiText(R.string.bluetooth_refused))
            else { askedPermissions = true; requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS) }
            return
        }
        if (!core.access.bluetoothOn()) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_BLUETOOTH)
            return
        }
        core.startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) beginSearch()
        else core.note(UiText(R.string.bluetooth_refused))
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (core.access.bluetoothOn()) core.startScan() else core.note(UiText(R.string.bluetooth_off))
    }

    private fun pick(radio: AppCore.FoundRadio) {
        readyAtTap = core.readyReports
        core.connect(radio.address, core.radioLabel(radio.name, radio.address))
        render()
    }

    override fun render() {
        val connected = core.connected
        state.text = if (connected) getString(R.string.radio_connected_to, core.radioLabel(core.settings.radioName, core.settings.radioAddress))
        else getString(R.string.radio_not_connected)
        disconnect.visibility = if (connected) View.VISIBLE else View.GONE
        searching.visibility = if (core.scanning) View.VISIBLE else View.GONE
        searchAgain.visibility = if (core.scanning) View.GONE else View.VISIBLE
        pairPicture?.let { picture ->
            picture.visibility = if (!connected && core.settings.radioAddress?.let { core.isAttempting(it) } == true) View.VISIBLE else View.GONE
            picture.update()
        }
        adapter.items = core.found
        adapter.notifyDataSetChanged()
        val tapped = readyAtTap
        if (connected && tapped != null && core.readyReports > tapped) {
            readyAtTap = null
            finish() // back to Chats, where the radio card turns green
        }
    }

    private inner class RadioAdapter : BaseAdapter() {
        var items: List<AppCore.FoundRadio> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(items[position])
    }

    private fun row(radio: AppCore.FoundRadio): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(this@RadioActivity, 72)
            setPadding(pad() / 2, pad() / 2, pad() / 2, pad() / 2)
            tag = "radio:${radio.address}"
            contentDescription = core.radioLabel(radio.name, radio.address)
        }
        row.addView(Ui.content(Ui.text(this, core.radioLabel(radio.name, radio.address), palette.text, 20f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (core.isAttempting(radio.address)) {
            row.addView(Ui.text(this, getString(R.string.radio_connecting), palette.muted).apply {
                tag = "connecting"
                setPadding(pad() / 2, 0, pad() / 2, 0)
            })
        }
        val bars = SignalBars.of(radio.rssi)
        row.addView(SignalBarsView(this, bars, palette.text, palette.outline).apply {
            tag = "bars:${radio.address}"
            contentDescription = getString(R.string.signal_bars, bars)
        }, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 28)))
        return row
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_BLUETOOTH = 2
    }
}

/** Four rising bars, [filled] of them solid: strength by shape and height, never by colour alone. */
class SignalBarsView(context: Context, private val filled: Int, private val on: Int, private val off: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = Ui.dp(context, 2).toFloat() }

    override fun onDraw(canvas: Canvas) {
        val gap = width / 12f
        val barWidth = (width - gap * 3) / 4f
        for (i in 0 until 4) {
            val left = i * (barWidth + gap)
            val top = height * (1f - (i + 1) / 4f)
            val solid = i < filled
            paint.color = if (solid) on else off
            paint.style = if (solid) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawRect(left, top, left + barWidth, height.toFloat(), paint)
        }
    }
}
