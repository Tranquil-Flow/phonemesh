package org.phonemesh.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PermissionBoundaryTest {
    @Test fun noMicrophonePermissionMeansNoCapture() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        val latch=CountDownLatch(1)
        var captured:FloatArray?=null
        var failure:String?=null
        MicrophoneCapture(context).use { microphone ->
            microphone.start { samples,error -> captured=samples; failure=error; latch.countDown() }
            assertTrue(latch.await(5,TimeUnit.SECONDS))
        }
        assertNull(captured)
        assertNotNull(failure)
    }
}
