package org.phonemesh.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.RegionResult
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.RegionStep

/** AppCore with the fake radio: the region flow end to end, without screens. */
@RunWith(AndroidJUnit4::class)
class RegionFlowTest {
    private val core get() = TestCore.core
    private val radio get() = TestCore.radio
    private fun <T> main(block: () -> T): T = TestCore.onMain(block)

    @After fun tearDown() = TestCore.uninstall()

    /** A remembered radio connects reporting [region], with the phone in [country]. */
    private fun connectRadio(region: String, country: String?) {
        TestCore.install(connected = false)
        TestCore.access.country = country
        main {
            core.settings.radioAddress = ADDRESS
            core.settings.radioName = "Kofi"
            radio.region = region
            radio.connected(true)
        }
    }

    /** The radio restarts and its new handshake reports [region]. */
    private fun restartWith(region: String) = main { radio.connected(false); radio.region = region; radio.connected(true) }

    @Test fun unsetRadioBlocksSendingAndKeepsTheDraft() {
        connectRadio("UNSET", "TG")
        val group = main { core.groupChatId() }
        main { core.setDraft(group, "Bonjour") }
        assertFalse(main { core.send(group) })
        assertEquals("Bonjour", main { core.draft(group) })
        assertTrue(radio.sent.isEmpty())
        assertTrue(main { core.history.messages(group).isEmpty() })
        assertFalse(main { core.settings.firstMessageSent })
        assertFalse(main { core.radioReady() })
        assertTrue("nothing is written without a tap", radio.regionWrites.isEmpty())
    }

    @Test fun yesWritesTheRegionAndTheRestartIsVerified() {
        connectRadio("UNSET", "TG")
        assertEquals(RegionStep.AskSet("TG"), main { core.region.step() })
        assertTrue(radio.regionWrites.isEmpty())
        main { core.region.yes() }
        assertEquals(listOf("EU_868"), radio.regionWrites)
        restartWith("EU_868")
        assertTrue(main { core.region.phase is RegionPhase.Done })
        assertEquals("TG", main { core.settings.radioCountry(ADDRESS) })
        // The green card is still showing: setup step 2 is not finished until it has gone.
        assertFalse(main { core.radioReady() })
        assertFalse(main { core.region.blocksSending })
        val deadline = System.currentTimeMillis() + RegionSetup.DONE_MS + 5_000
        while (main { core.region.phase } != RegionPhase.Idle && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(RegionPhase.Idle, main { core.region.phase })
        assertEquals(RegionStep.Ready, main { core.region.step() })
        assertTrue(main { core.radioReady() })
        assertEquals(listOf("EU_868"), radio.regionWrites)
    }

    @Test fun matchingRadioIsReadyWithoutAnyCard() {
        connectRadio("EU_868", "DE")
        assertEquals(RegionStep.Ready, main { core.region.step() })
        assertTrue(main { core.radioReady() })
        assertTrue(radio.regionWrites.isEmpty())
    }

    @Test fun aRadioWithNoCountryKnownIsLeftAlone() {
        TestCore.install()
        assertTrue(main { core.radioReady() })
        assertFalse(main { core.region.blocksSending })
    }

    /** D5: a set radio being changed sends nothing, the draft stays, and sending works again once verified. */
    @Test fun sendingIsBlockedWhileASetRadioIsBeingChanged() {
        connectRadio("US", "TG")
        assertEquals(RegionStep.AskChange("TG", "US"), main { core.region.step() })
        val group = main { core.groupChatId() }
        main { core.setDraft(group, "Bonjour"); core.region.yes() }
        assertEquals(RegionPhase.Working("EU_868", "TG", false), main { core.region.phase })
        var told = 0
        main { core.observe { change -> if (change == AppCore.Change.REGION) told++ } }
        assertFalse(main { core.send(group) })
        assertEquals("Bonjour", main { core.draft(group) })
        assertTrue(radio.sent.isEmpty())
        assertTrue(main { core.history.messages(group).isEmpty() })
        assertTrue("the screen is told to show the card", told > 0)
        // Still blocked while the radio is restarting.
        main { radio.connected(false) }
        assertFalse(main { core.send(group) })
        assertEquals("Bonjour", main { core.draft(group) })
        assertTrue(main { core.history.messages(group).isEmpty() })
        main { radio.region = "EU_868"; radio.connected(true) }
        assertTrue(main { core.region.phase is RegionPhase.Done })
        assertTrue(main { core.send(group) })
        assertEquals(1, radio.sent.size)
        assertNull(main { core.draft(group) })
    }

    /** A message that failed while the radio was away, in the group chat; the radio is back with the same region. */
    private fun failedMessage(): Message {
        val group = main { core.groupChatId() }
        // A message the radio reported it could not send (no radio at all makes a message wait instead).
        val failed = main {
            val m = core.history.addOutgoing(group, "Bonjour", "fr", System.currentTimeMillis())
            core.history.setStatus(m.id, DeliveryStatus.FAILED)
            core.history.message(m.id)!!
        }
        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertTrue(radio.sent.isEmpty())
        return failed
    }

    private fun status(id: Long) = main { core.history.message(id)!!.status }

    /** "Send again" is a send: nothing goes out while a set radio is being changed, and the message stays failed. */
    @Test fun sendAgainIsBlockedWhileASetRadioIsBeingChanged() {
        connectRadio("US", "TG")
        val failed = failedMessage()
        main { core.region.yes() }
        assertEquals(RegionPhase.Working("EU_868", "TG", false), main { core.region.phase })
        var told = 0
        main { core.observe { change -> if (change == AppCore.Change.REGION) told++ } }
        assertFalse(main { core.resend(failed.id) })
        assertTrue(radio.sent.isEmpty())
        assertEquals(DeliveryStatus.FAILED, status(failed.id))
        assertEquals(failed.packetId, main { core.history.message(failed.id)!!.packetId })
        assertTrue("the screen is told to show the card", told > 0)
        // Still blocked while the radio is restarting.
        main { radio.connected(false) }
        assertFalse(main { core.resend(failed.id) })
        assertTrue(radio.sent.isEmpty())
        assertEquals(DeliveryStatus.FAILED, status(failed.id))
        // Positive control: the region is verified, and the same message goes out.
        main { radio.region = "EU_868"; radio.connected(true) }
        assertTrue(main { core.region.phase is RegionPhase.Done })
        assertTrue(main { core.resend(failed.id) })
        assertEquals(1, radio.sent.size)
        assertEquals(DeliveryStatus.WAITING, status(failed.id))
        assertEquals("one shared post on port 303", listOf(303), radio.broadcasts.map { it.port })
        assertTrue("the message keeps a shared reference, not the radio's packet id", main { core.history.message(failed.id)!!.packetId!! } >= (1L shl 40))
        assertEquals(listOf("EU_868"), radio.regionWrites)
    }

    /** A radio with no region transmits nothing, by Send or by Send again. */
    @Test fun sendAgainIsBlockedWhileTheRadioHasNoRegion() {
        connectRadio("UNSET", "TG")
        val failed = failedMessage()
        assertTrue(main { core.region.blocksSending })
        assertFalse(main { core.resend(failed.id) })
        assertTrue(radio.sent.isEmpty())
        assertEquals(DeliveryStatus.FAILED, status(failed.id))
        assertTrue("nothing is written without a tap", radio.regionWrites.isEmpty())
        main { core.region.yes() }
        assertFalse(main { core.resend(failed.id) })
        assertTrue(radio.sent.isEmpty())
        restartWith("EU_868")
        assertTrue(main { core.resend(failed.id) })
        assertEquals(1, radio.sent.size)
        assertEquals(DeliveryStatus.WAITING, status(failed.id))
    }

    /** S3: a failure card can be left; Keep is reachable again and nothing more is written or reconnected. */
    @Test fun aFailedChangeCanBeLeftAndTheRadioKept() {
        connectRadio("US", "TG")
        main { radio.regionResult = RegionResult.DROPPED; core.region.yes() }
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), main { core.region.phase })
        assertFalse(main { core.radioReady() })
        main { core.region.back() }
        assertEquals(RegionPhase.Idle, main { core.region.phase })
        assertEquals(RegionStep.AskChange("TG", "US"), main { core.region.step() })
        main { core.region.keep() }
        assertTrue(main { core.radioReady() })
        assertEquals(1, radio.regionWrites.size)
        assertTrue(radio.connects.isEmpty())
    }

    /** D5 leaves other send failures alone: a set radio whose write failed still sends. */
    @Test fun aFailedChangeDoesNotBlockSending() {
        connectRadio("US", "TG")
        val group = main { core.groupChatId() }
        main { radio.regionResult = RegionResult.DROPPED; core.setDraft(group, "Bonjour"); core.region.yes() }
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), main { core.region.phase })
        assertTrue(main { core.send(group) })
        assertEquals(1, radio.sent.size)
    }

    /** D4: the region on hand before the radio restarts proves nothing; only the new handshake's value does. */
    @Test fun onlyTheRegionAfterTheRestartVerifiesTheWrite() {
        connectRadio("UNSET", "TG")
        main { core.region.yes() }
        // Same connection, another ready report, and the value already looks right: not a verification.
        main { radio.region = "EU_868"; radio.connected(true) }
        assertEquals(RegionPhase.Working("EU_868", "TG", false), main { core.region.phase })
        assertNull(main { core.settings.radioCountry(ADDRESS) })
        // The restart's handshake says the radio did not change: written once more, still not done.
        restartWith("UNSET")
        assertEquals(listOf("EU_868", "EU_868"), radio.regionWrites)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), main { core.region.phase })
        assertNull(main { core.settings.radioCountry(ADDRESS) })
        // Positive control: the next handshake carries the region.
        restartWith("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "TG", false), main { core.region.phase })
        assertEquals("TG", main { core.settings.radioCountry(ADDRESS) })
    }

    @Test fun keepIsRememberedAndNothingIsWritten() {
        connectRadio("US", "TG")
        main { core.region.keep() }
        assertTrue(main { core.radioReady() })
        assertEquals(setOf("TG"), main { core.settings.kept(ADDRESS) })
        restartWith("US")
        assertTrue(main { core.radioReady() })
        assertTrue(radio.regionWrites.isEmpty())
    }

    /** D2 through the real wiring: a refused region reaches neither the radio nor the memory. */
    @Test fun helperCannotWriteARegionThatIsNotOffered() {
        connectRadio("UNSET", "BJ")
        assertEquals(RegionStep.AskHelper("BJ"), main { core.region.step() })
        listOf("UNSET", "UNRECOGNIZED", "ITU1_2M", "ITU2_70CM", "ITU2_125CM").forEach { region ->
            main { core.region.helperChoose(region) }
            assertTrue(region, radio.regionWrites.isEmpty())
            assertEquals(region, RegionPhase.Idle, main { core.region.phase })
        }
        main { core.region.helperChoose("EU_868") }
        assertEquals(listOf("EU_868"), radio.regionWrites)
        restartWith("EU_868")
        assertTrue(main { core.settings.helperSet(ADDRESS) })
        assertEquals("BJ", main { core.settings.radioCountry(ADDRESS) })
    }

    /** D1: the wait reconnects the same radio by itself, and never restarts a connection still being made. */
    @Test fun theWaitReconnectsWithoutRestartingAConnectionInProgress() {
        connectRadio("UNSET", "TG")
        main { core.region.yes(); radio.connected(false) }
        val deadline = System.currentTimeMillis() + 8_000
        while (radio.connects.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(listOf(ADDRESS), radio.connects.toList())
        assertTrue(main { core.isConnecting(ADDRESS) })
        main { core.reconnectForRegion() }
        assertEquals(listOf(ADDRESS), radio.connects.toList())
        // The connection attempt ended without the radio: the next try is a new connection.
        main { radio.failConnect(); core.reconnectForRegion() }
        assertEquals(listOf(ADDRESS, ADDRESS), radio.connects.toList())
    }

    /** D1: a failed write leaves no reconnect running; Try again then writes on the live link. */
    @Test fun aFailedWriteStopsReconnectingAndTryAgainWrites() {
        connectRadio("UNSET", "TG")
        main { core.region.yes() }
        // Link lost, radio back unchanged, and the second write does not go out.
        main { radio.regionResult = RegionResult.DROPPED }
        restartWith("UNSET")
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), main { core.region.phase })
        assertEquals(2, radio.regionWrites.size)
        // The link goes again while the card waits: the old wait must not reconnect behind it.
        main { radio.connected(false) }
        Thread.sleep(RegionSetup.FIRST_RECONNECT_MS + 1_500)
        assertTrue("no reconnect after the failure", radio.connects.isEmpty())
        main { radio.connected(true) }
        assertEquals(RegionPhase.Failed(RegionFailure.TRY_AGAIN), main { core.region.phase })
        main { radio.regionResult = RegionResult.SENT; core.region.retry() }
        assertEquals(3, radio.regionWrites.size)
        assertEquals(RegionPhase.Working("EU_868", "TG", false), main { core.region.phase })
        restartWith("EU_868")
        assertEquals(RegionPhase.Done("EU_868", "TG", false), main { core.region.phase })
        assertTrue(radio.connects.isEmpty())
    }

    @Test fun aLineIsReadAloudAndASecondTapStopsIt() {
        TestCore.install()
        main { core.speakLine("Bonjour") }
        assertEquals(AppCore.LINE_PLAYBACK, main { core.playingId })
        main { core.speakLine("Bonjour") }
        assertNull(main { core.playingId })
    }

    private companion object { const val ADDRESS = "AA:BB:CC:DD:EE:01" }
}
