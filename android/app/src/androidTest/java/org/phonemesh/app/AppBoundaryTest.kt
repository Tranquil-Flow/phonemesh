package org.phonemesh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.BleMeshTransport
import org.phonemesh.ble.MeshRadio
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppBoundaryTest {
    /** The Phase 2 empty-state screenshot moved to the screenshot build (Task 16). */
    @Test fun appStartsWithoutPacksAndNeverRecordsOrSendsByItself() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        TestCore.install(voices=emptyList(),connected=false)
        val chats=Views.start(ChatsActivity::class.java)
        try {
            assertNotNull(Views.tagged(chats,"card_voice"))
            // The numbered guide has one current card; the later radio step is a slim row.
            assertNotNull(Views.tagged(chats,"step_row:2"))
            val conversation=Views.launch<ConversationActivity>(ConversationActivity.intent(context,TestCore.core.groupChatId()))
            val mic=checkNotNull(Views.tagged(conversation,"mic"))
            assertTrue(mic.alpha<0.5f)
            Views.tap(conversation,"mic")
            assertTrue(conversation.isFinishing)
            assertEquals(0,TestCore.speech.recordings)
            assertTrue(TestCore.speech.spoken.isEmpty())
            assertTrue(TestCore.radio.sent.isEmpty())
            assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        } finally {
            Views.finish(chats)
            TestCore.uninstall()
        }
    }

    @Test fun requestedPermissionsAreExactlyMicrophoneRadioPackDownloadsAndPlannedSending() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val requested=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        // Network is used only for setup-time pack downloads (PackDownloads).
        val expected=mutableSetOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.INTERNET,
            Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,
            // Planned messages sent while the app is closed (owner decision, 2026-10-12): an exact alarm and a short
            // foreground service; its notification is never asked for.
            Manifest.permission.USE_EXACT_ALARM,Manifest.permission.FOREGROUND_SERVICE,Manifest.permission.POST_NOTIFICATIONS)
        if(Build.VERSION.SDK_INT<=32) expected+=Manifest.permission.SCHEDULE_EXACT_ALARM
        // Legacy radio permissions are declared with maxSdkVersion and only requested on old Android.
        if(Build.VERSION.SDK_INT<=30) expected+=listOf(Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT<=28) expected+=Manifest.permission.ACCESS_COARSE_LOCATION
        assertEquals(expected,requested)
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.INTERNET))
    }

    @Test fun missingBluetoothPermissionCannotStartAScanOrAdmitASend() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN))
        val latch=CountDownLatch(1)
        var reason=""
        val transport=BleMeshTransport(context,object:MeshRadio.Listener {
            override fun onState(connected:Boolean,detail:String) {
                assertFalse(connected); reason=detail; latch.countDown()
            }
            override fun onText(sender:String,text:String,channel:Int,direct:Boolean) { fail("Unexpected radio message") }
            override fun onDevice(address:String,name:String,rssi:Int) { fail("Scan ran without permission") }
        })
        try {
            transport.scan()
            assertTrue(latch.await(5,TimeUnit.SECONDS))
            assertTrue(reason.contains("permission",ignoreCase=true))
            assertThrows(IllegalStateException::class.java) { transport.send("hello","!12345678",0) }
        } finally { transport.close() }
    }
}
