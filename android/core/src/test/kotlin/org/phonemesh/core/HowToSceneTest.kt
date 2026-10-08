package org.phonemesh.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HowToSceneTest {
    @Test fun aTrackHoldsItsKeysAndEasesBetweenThem() {
        val track = Track.of("0:0 .5:10 1:10")
        assertEquals(0f, track.at(0f), 1e-4f)
        assertEquals(10f, track.at(.5f), 1e-4f)
        assertEquals(10f, track.at(.75f), 1e-4f)
        assertEquals(5f, track.at(.25f), 1e-4f)
        assertTrue("eased, not linear", track.at(.125f) < 2.5f)
    }

    @Test fun keysAtTheSameTimeMakeAStep() {
        val track = Track.of("0:0 .3:0 .3:1 1:1")
        assertEquals(0f, track.at(.29f), 1e-4f)
        assertEquals(1f, track.at(.3f), 1e-4f)
        assertEquals(1f, track.at(.31f), 1e-4f)
    }

    @Test fun theLastKeyWinsAtRepeatedEndpointsAndInteriorTimes() {
        val track = Track.of("0:2 0:3 .5:4 .5:5 .5:6 1:7 1:8")
        assertEquals(3f, track.at(0f), 1e-4f)
        assertEquals(6f, track.at(.5f), 1e-4f)
        assertEquals(8f, track.at(1f), 1e-4f)
        assertEquals(3.5f, track.at(.25f), 1e-4f)
        assertEquals(6.5f, track.at(.75f), 1e-4f)
    }

    @Test fun timeOutsideTheLoopIsClamped() {
        val track = Track.of("0:2 1:4")
        assertEquals(2f, track.at(-1f), 1e-4f)
        assertEquals(4f, track.at(2f), 1e-4f)
    }

    @Test fun badSpecsAreRefused() {
        listOf("", "0:0", ".1:0 1:0", "0:0 .5:1", "0:0 .6:1 .5:0 1:0", "0:0 x 1:0", "0:0:1 1:0").forEach { spec ->
            assertThrows(spec, IllegalArgumentException::class.java) { Track.of(spec) }
        }
    }

    @Test fun nonFiniteKeysAreRefusedByBothConstructors() {
        listOf("0:NaN 1:0", "0:0 1:Infinity", "0:0 NaN:1 1:0", "0:0 Infinity:1 1:0").forEach { spec ->
            assertThrows(spec, IllegalArgumentException::class.java) { Track.of(spec) }
        }
        assertThrows(IllegalArgumentException::class.java) { Track(listOf(0f to Float.NaN, 1f to 0f)) }
        assertThrows(IllegalArgumentException::class.java) { Track(listOf(0f to 0f, Float.NaN to 1f, 1f to 0f)) }
    }

    @Test fun aSceneReadsItsTracksByName() {
        val scene = HowToScene("demo", 5_000, .5f, linkedMapOf("a" to "0:0 .5:1 1:0", "b" to "0:3 1:3"), mapOf("a" to 1f))
        assertEquals(listOf("a", "b"), scene.names)
        assertEquals(1, scene.index("b"))
        val out = floatArrayOf(-1f, -1f, 42f)
        scene.at(.5f, out)
        assertEquals(1f, out[0], 1e-4f)
        assertEquals(3f, out[1], 1e-4f)
        assertEquals(42f, out[2], 1e-4f)
        assertEquals(1f, scene.value("a", .5f), 1e-4f)
        assertThrows(IllegalArgumentException::class.java) { scene.index("c") }
        assertThrows(IllegalArgumentException::class.java) { scene.value("c", .5f) }
    }

    @Test fun aStillThatDoesNotShowWhatItClaimsIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            HowToScene("bad", 5_000, .1f, mapOf("a" to "0:0 .5:1 1:0"), mapOf("a" to 1f))
        }
    }

    @Test fun invalidStillTimesAndClaimsAreRefused() {
        listOf(-.1f, 1.1f, Float.NaN).forEach { still ->
            assertThrows(IllegalArgumentException::class.java) {
                HowToScene("bad", 5_000, still, mapOf("a" to "0:0 1:0"), mapOf("a" to 0f))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HowToScene("bad", 5_000, .5f, mapOf("a" to "0:0 1:0"), mapOf("missing" to 0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HowToScene("bad", 5_000, .5f, mapOf("a" to "0:0 1:0"), mapOf("a" to Float.NaN))
        }
    }
}
