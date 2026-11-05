package org.phonemesh.app

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v0.1.3 N2: the Settings switch for new-message alerts. Off by default; turning it on asks Android's permission only when
 * it is missing and only from here; a blocked state says so and opens Android's settings for this app. The request and
 * Android's settings screen are intercepted, so no permission dialog or system screen opens. Emulator only.
 */
@RunWith(AndroidJUnit4::class)
class AlertSettingsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var activity: SettingsActivity? = null
    private var monitor: Instrumentation.ActivityMonitor? = null

    @After fun tearDown() {
        monitor?.let { inst.removeMonitor(it) }
        Views.finish(activity)
        TestCore.uninstall()
    }

    private fun open(): SettingsActivity {
        val screen: SettingsActivity = Views.start(SettingsActivity::class.java)
        activity = screen
        return screen
    }

    private fun shown(screen: Activity, tag: String) = Views.tagged(screen, tag)?.visibility == View.VISIBLE

    @Test fun alertsStartOffAndSayWhatTheyShowAndWhen() {
        TestCore.install()
        val screen = open()
        val switch = Views.tagged(screen, "new_message_alerts") as Switch
        assertEquals("M'alerter des nouveaux messages", Views.textOf(switch))
        assertFalse(TestCore.onMain { switch.isChecked })
        assertEquals("Les alertes ne montrent ni noms ni mots.", Views.textOf(Views.tagged(screen, "alerts_private")!!))
        assertEquals("Si l'application est ouverte ou garde le relais.", Views.textOf(Views.tagged(screen, "alerts_scope")!!))
        assertFalse("nothing blocked while off", shown(screen, "alerts_blocked"))
        assertEquals(emptyList<String>(), A11yCheck.problems(screen))
    }

    @Test fun turningAlertsOnAsksForThePermissionOnlyWhenItIsMissing() {
        TestCore.install()
        TestCore.access.alertPermission = false
        val screen = open()
        val asked = ArrayList<String>()
        TestCore.onMain { screen.askPermission = { asked += it } }
        Views.tap(screen, "new_message_alerts")
        assertTrue(TestCore.core.settings.newMessageAlerts)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked.toList())
        Views.tap(screen, "new_message_alerts")
        assertFalse(TestCore.core.settings.newMessageAlerts)
        TestCore.access.alertPermission = true
        Views.tap(screen, "new_message_alerts")
        assertTrue(TestCore.core.settings.newMessageAlerts)
        assertEquals("not asked again once held", 1, asked.size)
        assertFalse("turning alerts on keeps nothing in the background", TestCore.core.settings.hubRoaming)
    }

    @Test fun blockedAlertsSayWhyAndOpenAndroidSettingsForThisApp() {
        TestCore.install()
        TestCore.alerts.allow = false
        TestCore.onMain { TestCore.core.setNewMessageAlerts(true) }
        val screen = open()
        assertTrue(shown(screen, "alerts_blocked"))
        assertEquals("Android bloque ces alertes.", Views.textOf(Views.tagged(screen, "alerts_blocked")!!))
        val filter = IntentFilter(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        monitor = inst.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        Views.tap(screen, "alerts_settings")
        assertEquals(1, monitor!!.hits)
        // Allowed again (for example in Android's settings): the line goes when the screen shows again.
        TestCore.alerts.allow = true
        TestCore.onMain { screen.onCoreChange(AppCore.Change.MESSAGES) }
        assertFalse(shown(screen, "alerts_blocked"))
        assertNull(Views.tagged(screen, "alerts_settings"))
    }

    @Test fun theSettingsIntentNamesThisApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent: Intent = SettingsActivity.alertSettings(context)
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }
}
