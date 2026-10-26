package org.phonemesh.app.demo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.phonemesh.app.AppCore
import org.phonemesh.app.DeviceAccess
import org.phonemesh.app.PhoneAccess
import org.phonemesh.app.R
import org.phonemesh.app.ScanActivity

/**
 * The demo app (docs/specs/2026-10-25-demo-build-design.md). Before any screen opens, the process-wide [AppCore] is
 * replaced by one built the normal way but with the demo's radio, device access and hub links, and the camera of
 * Scan a code by a picker. Every screen then gets the DEMO label, which opens the demo controls.
 */
class DemoApp : Application() {
    lateinit var world: DemoWorld
        private set

    override fun onCreate() {
        super.onCreate()
        world = DemoWorld(this)
        val core = AppCore.create(this, radioFactory = world::radioFor, access = DemoAccess(PhoneAccess(this)))
        core.hub = world.hub.wifiLink
        core.bluetoothHub = world.hub.bluetoothLink
        AppCore.replaceForTest(core)
        world.attach(core)
        ScanActivity.startCamera = { DemoScan.show(it, world, core) }
        registerActivityLifecycleCallbacks(DemoBand(world))
        DemoNotice.show(this)
    }

    companion object {
        fun world(context: Context): DemoWorld = (context.applicationContext as DemoApp).world
    }
}

/** The phone as the demo needs it: the demo radio needs no Bluetooth permission and Bluetooth is always on. */
class DemoAccess(private val phone: DeviceAccess) : DeviceAccess by phone {
    override fun radioPermissionsMissing(): List<String> = emptyList()
    override fun bluetoothOn(): Boolean = true
}

/**
 * The DEMO label on every app screen, in the middle of the status bar so it never covers the app. It only marks the
 * demo: Android keeps touches there, so the controls open from [DemoNotice] or their own launcher icon.
 */
class DemoBand(private val world: DemoWorld) : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val old = decor.findViewWithTag<View>(TAG)
        if (activity is DemoControlsActivity || !world.bandShown) { old?.let { decor.removeView(it) }; return }
        if (old != null) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val label = TextView(activity).apply {
            tag = TAG
            text = activity.getString(R.string.demo_band)
            contentDescription = activity.getString(R.string.demo_app_name)
            setTextColor(Color.BLACK)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xF2FFC107.toInt()) }
            elevation = dp(6).toFloat()
        }
        // In the middle of the status bar, where the system draws nothing, so it never covers the app's own screen.
        val bar = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it != 0 }?.let { activity.resources.getDimensionPixelSize(it) } ?: dp(24)
        val height = minOf(dp(20), bar)
        decor.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (bar - height) / 2 })
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object { const val TAG = "demo_band" }
}
