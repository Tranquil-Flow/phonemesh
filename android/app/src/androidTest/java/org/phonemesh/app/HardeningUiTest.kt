package org.phonemesh.app

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState

/** UI state injection only: no scan, connection, recording, playback or radio sends. */
@RunWith(AndroidJUnit4::class)
class HardeningUiTest {
    private fun descendants(v:View):List<View> = listOf(v) +
        if(v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun deliveryStatusIsPerPacketAndNeverSaysReadOrPlayed() {
        val inst=InstrumentationRegistry.getInstrumentation()
        val activity=inst.startActivitySync(Intent(inst.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            inst.runOnMainSync {
                activity.onState(true,"ready")
                activity.onDelivery(101L,DeliveryState.QUEUED,"queue accepted")
                activity.onDelivery(102L,DeliveryState.FAILED,"NO_ROUTE")
                activity.onDelivery(101L,DeliveryState.ACKNOWLEDGED,"peer ACK")
                val views=descendants(activity.window.decorView)
                val first=views.firstOrNull { it.contentDescription=="Delivery 101" } as? TextView
                val second=views.firstOrNull { it.contentDescription=="Delivery 102" } as? TextView
                assertNotNull("Each packet keeps its own status",first)
                assertNotNull(second)
                assertTrue(first!!.text.contains("Accusé de réception de la radio destinataire"))
                assertTrue(first.text.contains("lecture non confirmée"))
                assertTrue(second!!.text.contains("Échec"))
                assertTrue(second.text.contains("NO_ROUTE"))
                val banner=views.first { it.contentDescription=="Radio connection status" } as TextView
                assertTrue(banner.text.startsWith("Radio : connectée"))
                activity.onDelivery(103L,DeliveryState.UNCONFIRMED,"timeout")
                val unknown=descendants(activity.window.decorView).first { it.contentDescription=="Delivery 103" } as TextView
                assertTrue(unknown.text.contains("Livraison non confirmée"))
            }
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun connectionBannerSurvivesDiagnosticsAndOperationFeedback() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val views=descendants(activity.window.decorView)
                val banner=views.firstOrNull { it.contentDescription=="Radio connection status" } as? TextView
                assertNotNull("Connection needs its own permanent banner",banner)
                val operation=views.firstOrNull { it.contentDescription=="Operation status" } as? TextView
                assertNotNull("Operation feedback must be separate",operation)
                activity.onState(true,"Draining fromRadio (coalesced trigger)")
                assertTrue(banner!!.text.startsWith("Radio : connectée"))
                assertFalse(banner.text.contains("Draining"))
                activity.onText("!12345678","\u0000",0)
                assertTrue(operation!!.text.startsWith("Message non pris en charge"))
                assertTrue(banner.text.startsWith("Radio : connectée"))
                assertTrue((views.first { it.contentDescription=="Review and send" } as Button).isEnabled)
                activity.onState(false,"BLE radio disconnected: status=147")
                assertTrue(banner.text.startsWith("Radio : déconnectée"))
                assertFalse((views.first { it.contentDescription=="Review and send" } as Button).isEnabled)
                assertNotNull(views.firstOrNull { it.contentDescription=="Reconnect last radio" })
                assertNotNull(views.firstOrNull { it.contentDescription=="Offline model choice" })
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
