package org.phonemesh.app.demo

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.phonemesh.app.AppCore
import org.phonemesh.app.ChatsActivity
import org.phonemesh.app.Locales
import org.phonemesh.app.Palette
import org.phonemesh.app.Themes
import org.phonemesh.app.R
import org.phonemesh.app.SafetyIcons

/**
 * The demo controls: make the things happen that would happen in a real place (people adding and writing to you, the
 * radio going off, someone out of range, the hub going down) and start over. Opened from the DEMO label or its own
 * launcher icon.
 */
class DemoControlsActivity : Activity() {
    private val world by lazy { DemoApp.world(this) }
    private lateinit var column: LinearLayout
    private var ink = 0

    /** The app's chosen language and appearance, as every app screen ([org.phonemesh.app.ScreenActivity]) uses. */
    override fun attachBaseContext(newBase: Context) {
        val settings = AppCore.get(newBase).settings
        super.attachBaseContext(Themes.wrap(Locales.wrap(newBase, settings.language), settings.theme))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = Palette(this)
        ink = palette.text
        val background = palette.background
        window.decorView.setBackgroundColor(background)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(background); addView(column) })
    }

    override fun onResume() {
        super.onResume()
        build()
        DemoNotice.askOnce(this, world)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        DemoNotice.show(this)
    }

    private fun build() {
        column.removeAllViews()
        words(R.string.demo_controls_title, 28f, bold = true)
        words(R.string.demo_about, 17f)

        heading(R.string.demo_section_people)
        act(R.string.demo_kofi_writes) {
            when {
                !world.hasAccount() -> R.string.demo_need_name
                !world.kofiWrites() -> R.string.demo_need_kofi
                else -> 0
            }
        }
        act(R.string.demo_kofi_picture) {
            when {
                !world.hasAccount() -> R.string.demo_need_name
                !world.kofiSendsPicture() -> R.string.demo_need_kofi
                else -> 0
            }
        }
        act(R.string.demo_ama_adds) { if (world.amaAddsYou()) 0 else R.string.demo_need_name }
        act(R.string.demo_someone_posts) { world.someonePosts(); 0 }
        act(R.string.demo_stock_writes) { world.stockUserWrites(); 0 }
        act(R.string.demo_stock_direct) { world.stockUserWritesToYou(); 0 }
        flip(if (world.kofiAway) R.string.demo_kofi_back else R.string.demo_kofi_away) { world.setKofiAway(!world.kofiAway) }
        words(R.string.demo_scan_hint, 15f)

        heading(R.string.demo_section_phones)
        words(R.string.demo_phones_hint, 15f)
        for (who in DemoPerson.Who.values()) button(0, getString(R.string.demo_their_pictures, who.label)) { theirPictures(who) }

        heading(R.string.demo_section_radio)
        flip(if (world.radioOn) R.string.demo_radio_off else R.string.demo_radio_on) { world.setRadioOn(!world.radioOn) }
        val country = world.country?.let { java.util.Locale("", it).getDisplayCountry(resources.configuration.locales[0]) }
        button(0, getString(R.string.demo_country, country ?: getString(R.string.demo_country_own))) {
            world.country = DemoCountry.next(world.country)
            build()
        }
        words(R.string.demo_country_hint, 15f)

        heading(R.string.demo_section_hub)
        words(R.string.demo_hub_hint, 15f)
        flip(if (world.hub.isOn) R.string.demo_hub_off else R.string.demo_hub_on) { world.setHubPower(!world.hub.isOn) }
        flip(if (world.hub.wifiOn) R.string.demo_hub_wifi_off else R.string.demo_hub_wifi_on) { world.setHubWifi(!world.hub.wifiOn) }
        flip(if (world.hub.bluetoothOn) R.string.demo_hub_bluetooth_off else R.string.demo_hub_bluetooth_on) {
            world.setHubBluetooth(!world.hub.bluetoothOn)
        }

        heading(R.string.demo_section_demo)
        flip(if (world.bandShown) R.string.demo_band_hide else R.string.demo_band_show) { world.bandShown = !world.bandShown }
        button(R.string.demo_start_over) {
            AlertDialog.Builder(this)
                .setTitle(R.string.demo_start_over)
                .setMessage(R.string.demo_start_over_message)
                .setPositiveButton(R.string.demo_start_over) { _, _ -> getSystemService(ActivityManager::class.java).clearApplicationUserData() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** An event: it happens, and the app comes back so you see it arrive. A nonzero result is why it could not. */
    private fun act(label: Int, run: () -> Int) = button(label) {
        val refused = run()
        if (refused != 0) { Toast.makeText(this, refused, Toast.LENGTH_LONG).show(); return@button }
        // Opened from its own icon or the notification with no app screen beneath: open the chats rather than home.
        if (isTaskRoot) startActivity(Intent(this, ChatsActivity::class.java))
        finish()
    }

    /** A switch: the label says what pressing it does next. */
    private fun flip(label: Int, run: () -> Unit) = button(label) { run(); build() }

    /** Their phone's safety-picture screen, for comparing with yours (the same six, in the same order). */
    private fun theirPictures(who: DemoPerson.Who) = world.theirSafetyPictures(who) { icons ->
        if (isFinishing) return@theirSafetyPictures
        if (icons == null || icons.size != 6) {
            Toast.makeText(this, getString(R.string.demo_not_added, who.label), Toast.LENGTH_LONG).show()
            return@theirSafetyPictures
        }
        val grid = android.widget.GridLayout(this).apply { columnCount = 3; setPadding(dp(16), dp(16), dp(16), dp(8)) }
        for (icon in icons) grid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(android.widget.ImageView(this@DemoControlsActivity).apply { setImageResource(SafetyIcons.drawables[icon]) },
                LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(TextView(this@DemoControlsActivity).apply { setText(SafetyIcons.names[icon]); setTextColor(ink); textSize = 16f; gravity = android.view.Gravity.CENTER })
        })
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.demo_their_pictures_title, who.label))
            .setView(grid)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun button(label: Int, onClick: () -> Unit) = button(label, getString(label), onClick)

    private fun button(@Suppress("UNUSED_PARAMETER") label: Int, title: String, onClick: () -> Unit) {
        column.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            minHeight = dp(56)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun heading(label: Int) = words(label, 21f, bold = true, top = 28)

    private fun words(label: Int, size: Float, bold: Boolean = false, top: Int = 8) {
        column.addView(TextView(this).apply {
            setText(label)
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
