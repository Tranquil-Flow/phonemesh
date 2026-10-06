package org.phonemesh.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Spinner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.BleMeshTransport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppBoundaryTest {
    private fun descendants(view:View):List<View> = listOf(view) +
        if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun appLaunchesWithoutModelsAndDoesNotAutoRecordOrSend() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val drawn=CountDownLatch(1)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views=descendants(activity.window.decorView)
                assertNotNull(views.firstOrNull { it.contentDescription=="Record speech" })
                assertFalse((views.first { it.contentDescription=="Review and send" } as Button).isEnabled)
                assertTrue((views.first { it is Spinner } as Spinner).count>=9)
                assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                // Only this synthetic empty-screen UI test permits a screenshot.
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.window.decorView.invalidate()
                activity.window.decorView.postOnAnimation {
                    activity.window.decorView.postOnAnimation { drawn.countDown() }
                }
            }
            assertTrue(drawn.await(5,TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val screenshot=instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(screenshot)
            File(context.getExternalFilesDir(null),"ui-empty-state.png").outputStream().use {
                checkNotNull(screenshot).compress(Bitmap.CompressFormat.PNG,100,it)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.finish()
            }
        }
    }

    @Test fun missingBluetoothPermissionCannotStartAScanOrAdmitASend() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN))
        val latch=CountDownLatch(1)
        var reason=""
        val transport=BleMeshTransport(context,object:BleMeshTransport.Listener {
            override fun onState(connected:Boolean,detail:String) {
                assertFalse(connected); reason=detail; latch.countDown()
            }
            override fun onText(sender:String,text:String,channel:Int) { fail("Unexpected radio message") }
            override fun onDevice(address:String,name:String) { fail("Scan ran without permission") }
        })
        try {
            transport.scan()
            assertTrue(latch.await(5,TimeUnit.SECONDS))
            assertTrue(reason.contains("permission",ignoreCase=true))
            assertThrows(IllegalStateException::class.java) { transport.send("hello","!12345678",0) }
        } finally { transport.close() }
    }
}
