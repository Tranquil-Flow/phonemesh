package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every registered picture loops, lasts 5 to 8 s, and has an action visible in its still. */
class HowToScenesTest {
    @Test fun theSetupScenesAreThere() =
        assertEquals(listOf("download", "friend", "power", "close", "search", "pair", "hello", "lost"), HowToScenes.SETUP)

    @Test fun theTalkingScenesAreThere() =
        assertEquals(listOf("cancel", "lock", "listen", "delivery", "failed", "arrive"), HowToScenes.TALKING)

    @Test fun talkingActionsMatchTheApprovedKeyframes() {
        fun key(scene: String, track: String, t: Float, expected: Float) =
            assertEquals("$scene.$track at $t", expected, HowToScenes.get(scene).value(track, t), 1e-4f)
        key("cancel", "recording", .14f, 1f)
        key("cancel", "finger.dx", .6f, -125f)
        key("cancel", "lid", .58f, -35f)
        key("cancel", "recording", .62f, 0f)
        key("lock", "finger.dy", .52f, -62f)
        key("lock", "shackle", .58f, 0f)
        key("lock", "stop", .6f, 1f)
        key("lock", "finger.dy", .74f, 30f)
        key("listen", "sound", .22f, 1f)
        key("listen", "sound", .5f, 0f)
        key("listen", "finger.dx", .62f, 80f)
        key("listen", "fly.dy", .86f, -34f)
        key("listen", "fly.alpha", .86f, 0f)
        key("delivery", "waiting", .3f, 1f)
        key("delivery", "sent", .33f, 1f)
        key("delivery", "waiting", .33f, 0f)
        key("delivery", "rings", .36f, 1f)
        key("delivery", "delivered", .66f, 1f)
        key("delivery", "sent", .66f, 0f)
        key("failed", "finger.dy", .28f, 2f)
        key("failed", "failed", .33f, 0f)
        key("failed", "waiting", .33f, 1f)
        key("failed", "spin", .7f, 720f)
        key("failed", "delivered", .73f, 1f)
        key("arrive", "pulse", .22f, 1f)
        key("arrive", "message.dx", .28f, 0f)
        key("arrive", "message.alpha", .28f, 1f)
        key("arrive", "finger.dy", .48f, 2f)
        key("arrive", "sound", .52f, 1f)
        key("arrive", "sound", .94f, 0f)
    }

    @Test fun lockReleasesThenTapsTheVisibleStopSquareBeforeResetting() {
        val scene = HowToScenes.get("lock")
        fun key(track: String, time: Float, expected: Float) =
            assertEquals("lock.$track at $time", expected, scene.value(track, time), 1e-4f)
        key("finger.dx", .52f, 0f)
        key("finger.dy", .52f, -62f)
        key("shackle", .6f, 0f)
        key("stop", .6f, 1f)
        key("finger.dx", .76f, 30f)
        key("finger.dy", .76f, 30f)
        key("stop", .76f, 1f)
        key("finger.dx", .82f, 0f)
        key("finger.dy", .82f, 0f)
        key("finger.dx", .86f, 0f)
        key("finger.dy", .86f, 2f)
        key("stop", .86f, 1f)
        key("finger.dx", .9f, 0f)
        key("finger.dy", .9f, 0f)
        key("finger.dx", .96f, 30f)
        key("finger.dy", .96f, 30f)
        key("stop", .96f, 1f)
        assertEquals(.6f, scene.still, 0f)
        key("finger.dy", scene.still, -62f)
    }

    @Test fun theProblemScenesAreThere() {
        assertEquals(listOf("bt", "short", "notheard", "mic"), HowToScenes.PROBLEMS)
        assertEquals(listOf("allow", "dlfail", "damaged", "refused", "older", "space", "wait", "toolong", "playfail"), HowToScenes.RARE)
        assertEquals(listOf(HowToScenes.SETUP, HowToScenes.TALKING, HowToScenes.PROBLEMS, HowToScenes.RARE), HowToScenes.groups)
    }

    @Test fun problemAndRareActionsMatchTheApprovedKeyframes() {
        fun key(scene: String, track: String, t: Float, expected: Float) =
            assertEquals("$scene.$track at $t", expected, HowToScenes.get(scene).value(track, t), 1e-4f)
        key("bt", "switch", .28f, 0f)
        key("bt", "switch", .36f, 1f)
        key("bt", "finger.dy", .28f, 2f)
        key("short", "bad", .34f, 1f)
        key("short", "hold", .75f, 1f)
        key("short", "hold", .76f, 0f)
        key("notheard", "phone.dx", .1f, 60f)
        key("notheard", "phone.dx", .42f, 0f)
        key("notheard", "bars", .5f, 1f)
        key("mic", "keyboard", .54f, 1f)
        key("mic", "finger.dy", .52f, 2f)
        key("allow", "allow", .4f, 1f)
        key("allow", "finger.dy", .4f, 2f)
        key("dlfail", "fail", .5f, 0f)
        key("dlfail", "dots", .54f, 1f)
        key("damaged", "shake.dx", .05f, -3f)
        key("damaged", "shake.alpha", .36f, 0f)
        key("damaged", "new.alpha", .46f, 1f)
        key("damaged", "new.dy", .6f, 22f)
        key("damaged", "fill", .88f, 1f)
        key("refused", "box.dx", .35f, 82f)
        key("refused", "box.dx", .55f, 20f)
        key("older", "box.dx", .35f, 82f)
        key("older", "bad", .42f, 1f)
        key("older", "ok", .66f, 1f)
        key("space", "trash.dx", .52f, 90f)
        key("space", "trash.alpha", .56f, 0f)
        key("space", "voice.dx", .78f, -92f)
        key("wait", "bar", .7f, 1f)
        key("wait", "button", .74f, 1f)
        key("wait", "sand", .72f, 0f)
        key("wait", "finger.dy", .88f, 2f)
        key("toolong", "sweep", .48f, 360f)
        key("toolong", "red", .5f, 1f)
        key("toolong", "snip", .5f, 1f)
        key("toolong", "fall.dy", .82f, 40f)
        key("toolong", "fall.alpha", .82f, 0f)
        key("playfail", "light", .5f, 1f)
        key("playfail", "arrow", .36f, 1f)
    }

    @Test fun waitingStillShowsTheCompletedButtonAndCheck() {
        val scene = HowToScenes.get("wait")
        assertEquals(1f, scene.value("bar", scene.still), 1e-4f)
        assertEquals(1f, scene.value("button", scene.still), 1e-4f)
        assertEquals(1f, scene.value("check", scene.still), 1e-4f)
        assertEquals(0f, scene.value("sand", scene.still), 1e-4f)
    }

    @Test fun everyListedSceneIsRegisteredOnce() {
        val listed = HowToScenes.groups.flatten()
        assertEquals(listed.size, listed.toSet().size)
        assertEquals(listed.toSet(), HowToScenes.all.keys)
        listed.forEach { assertEquals(it, HowToScenes.get(it).name) }
        assertThrows(IllegalArgumentException::class.java) { HowToScenes.get("missing") }
    }

    @Test fun everySceneLoopsBackToItsFirstFrame() {
        HowToScenes.all.values.forEach { scene ->
            scene.names.forEach { track ->
                assertEquals("${scene.name}.$track", scene.value(track, 0f), scene.value(track, 1f), 1e-3f)
            }
        }
    }

    @Test fun everySceneLastsFiveToEightSeconds() {
        HowToScenes.all.values.forEach { assertTrue(it.name, it.durationMs in 5_000L..8_000L) }
        assertEquals(5_000L, HowToScenes.get("power").durationMs)
    }

    @Test fun everyStillFrameShowsTheAction() {
        HowToScenes.all.values.forEach { scene ->
            assertTrue("${scene.name} names what its still shows", scene.stillShows.isNotEmpty())
            scene.stillShows.forEach { (track, value) ->
                assertEquals("${scene.name}.$track at ${scene.still}", value, scene.value(track, scene.still), .05f)
            }
        }
    }

    @Test fun aFingerThatPressesIsBackOnItsButtonInTheStill() {
        HowToScenes.all.values.filter { "finger.dx" in it.names }.forEach { scene ->
            assertTrue("${scene.name}: the still should show the finger on its target",
                kotlin.math.abs(scene.value("finger.dx", scene.still)) < 1f || "finger.dx" in scene.stillShows)
        }
    }

    @Test fun setupActionsMatchTheApprovedKeyframes() {
        fun key(scene: String, track: String, t: Float, expected: Float) =
            assertEquals("$scene.$track at $t", expected, HowToScenes.get(scene).value(track, t), 1e-4f)
        key("download", "fill", .75f, 1f)
        key("download", "dots", .78f, 0f)
        key("friend", "box.dx", .22f, 45f)
        key("friend", "box.dy", .22f, -16f)
        key("friend", "box.dx", .38f, 90f)
        key("friend", "box.alpha", .43f, 0f)
        key("friend", "box.dx", .65f, 45f)
        key("friend", "fill", .88f, 1f)
        key("power", "finger.dy", .28f, 3f)
        key("power", "led", .3f, 1f)
        key("power", "led", .41f, 0f)
        key("power", "on", .36f, 1f)
        key("close", "radio.dx", .08f, 80f)
        key("close", "radio.dx", .5f, 0f)
        key("search", "rings", .66f, 0f)
        key("search", "link", .74f, 1f)
        key("pair", "d1", .33f, 1f)
        key("pair", "d2", .43f, 1f)
        key("pair", "d3", .53f, 1f)
        key("pair", "d4", .63f, 1f)
        key("hello", "hold", .75f, 1f)
        key("hello", "hold", .76f, 0f)
        key("hello", "bars", .72f, 1f)
        key("hello", "bars", .76f, 0f)
        key("lost", "finger.dy", .18f, 3f)
        key("lost", "led", .2f, 1f)
        key("lost", "radio.dx", .62f, -62f)
        key("lost", "bad", .64f, 0f)
    }

    @Test fun fillingAndArrivingTracksHoldBeforeReturningForTheLoop() {
        listOf(
            Triple("download", "fill", 1f),
            Triple("friend", "fill", 1f),
            Triple("close", "radio.dx", 0f),
            Triple("search", "link", 1f),
            Triple("pair", "d4", 1f),
            Triple("lost", "radio.dx", -62f),
        ).forEach { (name, track, expected) ->
            assertEquals("$name.$track", expected, HowToScenes.get(name).value(track, .94f), 1e-4f)
        }
    }

    @Test fun freeRunningDrawingLoopsAreNotSceneTracks() {
        val freeRunning = setOf("ring", "blink", "vb", "wave", "chev", "btp", "mouth", "dot")
        HowToScenes.all.values.forEach { scene ->
            assertTrue(scene.name, scene.names.none { it in freeRunning })
        }
    }

    @Test fun namedValuesAndTheReusableFrameAgreeThroughoutEachLoop() {
        HowToScenes.all.values.forEach { scene ->
            val out = FloatArray(scene.names.size)
            for (frame in 0..200) {
                val t = frame / 200f
                scene.at(t, out)
                scene.names.forEachIndexed { index, track ->
                    assertTrue("${scene.name}.$track at $t", out[index].isFinite())
                    assertEquals("${scene.name}.$track at $t", scene.value(track, t), out[index], 1e-4f)
                }
            }
        }
    }
}
