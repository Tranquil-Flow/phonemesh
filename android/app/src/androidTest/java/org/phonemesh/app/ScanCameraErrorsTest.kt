package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled real callback boundary, not optical camera or permission qualification. */
@RunWith(AndroidJUnit4::class)
class ScanCameraErrorsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val originalStart = ScanActivity.startCamera
    private var screen: ScanActivity? = null
    private lateinit var callbacks: ScanActivity.CameraCallbacks
    private var deviceClosed = 0
    private var sessionClosed = 0
    private var configured = 0
    private var repeating = 0
    private var opens = 0

    private val ownedActivities = ArrayList<android.app.Activity>()
    private fun <T : android.app.Activity> start(type: Class<T>): T =
        Views.start(type).also { ownedActivities += it }
    private fun finish(activity: android.app.Activity?) {
        if (activity == null) return
        Views.finish(activity)
        Views.waitUntil { activity.isDestroyed }
    }
    private fun resumed(activity: android.app.Activity) {
        Views.waitUntil { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getLifecycleStageOf(activity) == androidx.test.runner.lifecycle.Stage.RESUMED }
    }

    @After fun tearDown() {
        try {
            ownedActivities.asReversed().forEach { finish(it) }
        } finally {
            TestCore.onMain { ScanActivity.startCamera = originalStart }
            TestCore.uninstall()
        }
    }

    private fun launch(language: String = "en-US", withParent: Boolean = false): ScanActivity {
        TestCore.install(language = language, connected = false)
        TestCore.onMain {
            ScanActivity.startCamera = { activity ->
                activity.beginCameraAttempt { callbacks = it; opens++ }
            }
        }
        if (withParent) start(AddSomeoneActivity::class.java)
        return start(ScanActivity::class.java).also { screen = it }
    }

    private fun opened() = callbacks.opened({ deviceClosed++ }) { configured++ }
    private fun running() {
        opened()
        callbacks.configured({ sessionClosed++ }) { repeating++ }
    }
    private fun feedback(activity: ScanActivity, language: String = "en-US") {
        val expected = if (language == "fr") "Caméra indisponible. Revenez puis réessayez."
            else "Camera unavailable. Go back and try again."
        // Direct assertion, not a timeout: callback dispatch has completed on main.
        assertNotNull("surviving scanner must explain camera failure: " + TestCore.onMain {
            "destroyed=${activity.isDestroyed} finishing=${activity.isFinishing} taskRoot=${activity.isTaskRoot} " +
                "stage=${androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)} " +
                "text=${Views.descendants(activity.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }}"
        }, Views.withText(activity, expected))
        assertFalse(TestCore.onMain { activity.isFinishing })
        assertNotNull("recovery remains reachable", Views.tagged(activity, "back"))
    }

    @Test fun openExceptionShowsActionableEnglishFeedback() {
        val a = launch()
        // End the held attempt using a real lifecycle transition before injecting an open exception.
        val cover = start(AddSomeoneActivity::class.java)
        TestCore.onMain {
            ScanActivity.startCamera = { activity ->
                activity.beginCameraAttempt { callbacks = it; throw IllegalStateException("injected open") }
            }
        }
        // Capture escaped exceptions inside the injected operation boundary, not the Android lifecycle.
        var escaped: Throwable? = null
        var attempted = false
        TestCore.onMain {
            val throwingStart = ScanActivity.startCamera
            ScanActivity.startCamera = { activity ->
                escaped = runCatching { throwingStart(activity) }.exceptionOrNull()
                attempted = true
            }
        }
        finish(cover)
        Views.waitUntil { attempted }
        assertNull("open errors must not escape into the activity lifecycle", escaped)
        feedback(a)
    }

    @Test fun setupExceptionClosesDeviceAndExplains() {
        val a = launch()
        val escaped = TestCore.onMain {
            runCatching { callbacks.opened({ deviceClosed++ }) { throw IllegalStateException("injected setup") } }.exceptionOrNull()
        }
        assertNull("setup failure must not escape callback", escaped)
        feedback(a)
        assertEquals(1, deviceClosed)
    }

    @Test fun repeatingExceptionClosesSessionAndDeviceAndExplains() {
        val a = launch()
        TestCore.onMain {
            opened()
            callbacks.configured({ sessionClosed++ }) { throw IllegalStateException("injected repeating request") }
        }
        feedback(a)
        assertEquals(1, sessionClosed)
        assertEquals(1, deviceClosed)
    }

    @Test fun configurationFailureKeepsScannerAndClosesResources() {
        val a = launch()
        TestCore.onMain { opened(); callbacks.configureFailed { sessionClosed++ } }
        feedback(a)
        assertEquals(1, deviceClosed)
        assertEquals(1, sessionClosed)
    }

    @Test fun errorExplainsInFrenchAndClosesRunningResources() {
        val a = launch("fr")
        TestCore.onMain { running(); callbacks.error { deviceClosed++ } }
        feedback(a, "fr")
        assertEquals(1, deviceClosed)
        assertEquals(1, sessionClosed)
    }

    @Test fun disconnectExplainsAndBackLeavesScanner() {
        // Match the real Add someone → scanner stack, not Android 12 task-root back-to-home.
        val a = launch(withParent = true)
        TestCore.onMain { running(); callbacks.disconnected { deviceClosed++ } }
        feedback(a)
        assertEquals(1, deviceClosed)
        assertEquals(1, sessionClosed)
        Views.tap(a, "back")
        Views.waitUntil { a.isFinishing }
        assertTrue(TestCore.onMain { a.isFinishing })
        Views.waitUntil { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is AddSomeoneActivity } }
    }

    @Test fun failedAttemptRejectsLateOpenConfigureAndDecodedFrame() {
        val a = launch()
        TestCore.onMain { callbacks.error { deviceClosed++ } }
        feedback(a)
        val note = TestCore.onMain { TestCore.core.lastNote }
        var lateDeviceClosed = 0
        var lateSessionClosed = 0
        TestCore.onMain {
            callbacks.opened({ lateDeviceClosed++ }) { configured++ }
            callbacks.configured({ lateSessionClosed++ }) { repeating++ }
            callbacks.frame("not a Phonemesh code")
            a.beginCameraAttempt { opens++ }
        }
        assertEquals(0, configured)
        assertEquals(0, repeating)
        assertEquals(1, lateDeviceClosed)
        assertEquals(1, lateSessionClosed)
        assertEquals(1, opens)
        assertEquals(note, TestCore.onMain { TestCore.core.lastNote })
        feedback(a)
    }

    @Test fun pausedAttemptRejectsLateCallbacksAndFrameWithoutShowingFailure() {
        val a = launch()
        TestCore.onMain { running() }
        val old = callbacks
        val cover = start(AddSomeoneActivity::class.java)
        val note = TestCore.onMain { TestCore.core.lastNote }
        var lateClosed = 0
        TestCore.onMain {
            old.opened({ lateClosed++ }) { configured++ }
            old.configured({ lateClosed++ }) { repeating++ }
            old.error { lateClosed++ }
            old.configureFailed { lateClosed++ }
            old.frame("not a Phonemesh code")
        }
        assertEquals(1, deviceClosed)
        assertEquals(1, sessionClosed)
        assertEquals(4, lateClosed)
        assertEquals(1, configured)
        assertEquals(1, repeating)
        assertEquals(note, TestCore.onMain { TestCore.core.lastNote })
        assertFalse(TestCore.onMain { a.isFinishing })
        finish(cover)
        Views.waitUntil { opens == 2 }
        assertNull(Views.withText(a, "Camera unavailable. Go back and try again."))
    }

    @Test fun oldAttemptCannotPoisonResumedAttempt() {
        val a = launch()
        val old = callbacks
        finish(start(AddSomeoneActivity::class.java))
        Views.waitUntil { opens == 2 }
        val note = TestCore.onMain { TestCore.core.lastNote }
        var staleClosed = 0
        TestCore.onMain {
            running()
            old.error { staleClosed++ }
            old.opened({ staleClosed++ }) { configured++ }
            old.configured({ staleClosed++ }) { repeating++ }
            old.frame("not a Phonemesh code")
        }
        assertEquals(note, TestCore.onMain { TestCore.core.lastNote })
        assertEquals(3, staleClosed)
        assertEquals(0, deviceClosed)
        assertEquals(0, sessionClosed)
        assertEquals(1, configured)
        assertEquals(1, repeating)
        assertNull(Views.withText(a, "Camera unavailable. Go back and try again."))
    }

    @Test fun failedScreenDoesNotRestartWhenUncoveredButFreshScannerCanStart() {
        val a = launch(withParent = true)
        TestCore.onMain { callbacks.error { deviceClosed++ } }
        feedback(a)
        // NEW_TASK to the existing AddSomeone task root reuses it instead of creating a cover.
        // A different screen gives startActivitySync a real new instance and preserves the scanner.
        finish(start(SettingsActivity::class.java))
        resumed(a)
        assertEquals("failed instance requires explicit Back and re-entry", 1, opens)
        feedback(a)
        Views.tap(a, "back")
        Views.waitUntil { a.isDestroyed }
        val fresh = start(ScanActivity::class.java).also { screen = it }
        assertEquals(2, opens)
        TestCore.onMain { running() }
        assertEquals(1, repeating)
        assertFalse(TestCore.onMain { fresh.isFinishing })
        assertNull(Views.withText(fresh, "Camera unavailable. Go back and try again."))
    }

    @Test fun duplicateOpenWhilePendingDoesNotStartAnotherCamera() {
        val a = launch()
        TestCore.onMain { a.beginCameraAttempt { opens++ } }
        assertEquals("one pending camera open per resumed instance", 1, opens)
    }

    @Test fun activeFrameStillUsesUnchangedDecoderRouting() {
        val a = launch()
        TestCore.onMain { callbacks.frame("not a Phonemesh code") }
        assertEquals(R.string.add_bad_code, TestCore.onMain { TestCore.core.lastNote?.id })
        assertFalse(TestCore.onMain { a.isFinishing })
    }

    @Test fun pauseClosesAllHandlesEvenWhenSessionCloseThrows() {
        launch()
        TestCore.onMain {
            opened()
            callbacks.configured({ sessionClosed++; throw IllegalStateException("injected close") }) { repeating++ }
        }
        start(AddSomeoneActivity::class.java)
        assertEquals(1, sessionClosed)
        assertEquals(1, deviceClosed)
    }
}
