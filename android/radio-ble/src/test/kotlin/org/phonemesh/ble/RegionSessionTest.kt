package org.phonemesh.ble

import com.google.protobuf.ByteString
import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.AdminProtos.AdminMessage
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode

/** The transport's region boundary, driven directly: what reaches the radio, and what a caller is told. */
class RegionSessionTest {
    private data class Write(val admin: AdminMessage, val to: Int, val wantResponse: Boolean)

    private val writes = mutableListOf<Write>()
    private val timers = mutableListOf<Pair<Runnable, Long>>()
    private val results = mutableListOf<RegionResult>()
    private var ready = true
    private var localNode: Int? = 0x0a0b0c0d
    /** Number of writes the fake link accepts before refusing. */
    private var accept = Int.MAX_VALUE
    /** Whether the fake link has room for a whole region transaction, and what it was asked about. */
    private var roomy = true
    private val roomAsked = mutableListOf<List<AdminMessage.PayloadVariantCase>>()
    private val session = RegionSession(
        ready = { ready },
        local = { localNode },
        write = { admin, to, wantResponse -> (writes.size < accept).also { if (it) writes += Write(admin, to, wantResponse) } },
        room = { messages, to ->
            assertEquals(localNode, to)
            roomAsked += messages.map { it.payloadVariantCase }
            roomy
        },
        later = { task, delay -> timers += task to delay },
        cancel = { task -> timers.removeAll { it.first === task } },
        finish = { done, result -> done(result) },
    )
    private val done: (RegionResult) -> Unit = { results += it }

    private val local = 0x0a0b0c0dL
    private val passkey = ByteString.copyFrom(byteArrayOf(9, 8, 7))
    private val handshake: LoRaConfig = LoRaConfig.newBuilder()
        .setUsePreset(true).setModemPreset(LoRaConfig.ModemPreset.LONG_FAST).setRegion(RegionCode.US)
        .setHopLimit(3).setTxEnabled(true).setTxPower(20).setChannelNum(2).build()
    /** What the radio answers when asked now: differs from the handshake so the source of a copy is visible. */
    private val asked: LoRaConfig = handshake.toBuilder().setTxPower(11).setHopLimit(5).build()
    private val refused = listOf(
        "ITU1_2M", "ITU2_2M", "ITU3_2M", "ITU1_70CM", "ITU2_70CM", "ITU3_70CM", "ITU2_125CM",
        "UNSET", "UNRECOGNIZED", "MARS", "", "eu_868",
    )

    private fun setConfigs() = writes.filter { it.admin.hasSetConfig() }
    private fun timeout() = timers.single().first.run()

    // --- region(): D4, only this connection's handshake counts -------------------------------------------------

    @Test fun regionIsUnknownUntilTheHandshakeIsComplete() {
        ready = false
        assertNull(session.region())
        session.onSettings(handshake)
        assertNull(session.region())
        ready = true
        assertEquals("US", session.region())
    }

    /** Negative control: what an earlier connection reported is gone once a new connection or handshake starts. */
    @Test fun aNewConnectionForgetsTheOldSettings() {
        session.onSettings(handshake)
        assertEquals("US", session.region())
        session.reset()
        // Even if the link says ready, there is no region until this handshake's own settings arrive.
        assertNull(session.region())
        // And the stale settings are never the base of a write.
        session.start("EU_868", done)
        timeout()
        assertEquals(listOf(RegionResult.NO_SETTINGS), results)
        assertTrue(setConfigs().isEmpty())
        assertEquals(1, writes.size)
    }

    /** Positive control: the new handshake's own settings are what is read and what is copied. */
    @Test fun theFreshHandshakeIsWhatCounts() {
        session.onSettings(handshake)
        session.reset()
        val fresh = handshake.toBuilder().setRegion(RegionCode.EU_868).setTxPower(7).build()
        session.onSettings(fresh)
        assertEquals("EU_868", session.region())
        session.start("EU_433", done)
        timeout()
        assertEquals(listOf(RegionResult.SENT), results)
        assertEquals(fresh.toBuilder().setRegion(RegionCode.EU_433).build(), setConfigs().single().admin.setConfig.lora)
    }

    @Test fun anUnknownFirmwareRegionIsNamedAndIsNotUnset() {
        session.onSettings(handshake.toBuilder().setRegionValue(99).build())
        assertEquals("UNRECOGNIZED", session.region())
        session.onSettings(handshake.toBuilder().setRegion(RegionCode.UNSET).build())
        assertEquals("UNSET", session.region())
        session.onSettings(LoRaConfig.getDefaultInstance())
        assertEquals("UNSET", session.region())
    }

    // --- start(): D2, the allowed list is enforced before any write ---------------------------------------------

    @Test fun aRegionThatIsNotAllowedIsRefusedBeforeAnyWrite() {
        session.onSettings(handshake)
        refused.forEach { name ->
            try {
                session.start(name, done)
                fail("accepted '$name'")
            } catch (_: IllegalArgumentException) {
            }
            try {
                RegionSession.writable(name)
                fail("writable '$name'")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(writes.isEmpty())
        assertTrue(timers.isEmpty())
        assertTrue(results.isEmpty())
        // A refused call leaves nothing pending: the next allowed write starts normally.
        session.start("EU_868", done)
        assertEquals(1, writes.size)
    }

    /** Positive control: an allowed region asks the local radio for its LoRa settings and waits 10 s. */
    @Test fun anAllowedRegionAsksTheLocalRadioFirst() {
        session.onSettings(handshake)
        assertEquals(RegionCode.EU_868, RegionSession.writable("EU_868"))
        session.start("EU_868", done)
        assertEquals(listOf(Write(RegionWriter.getLora(), 0x0a0b0c0d, true)), writes)
        assertEquals(listOf(10_000L), timers.map { it.second })
        assertTrue(results.isEmpty())
    }

    @Test fun everyOfferedRegionIsAccepted() = RadioRegions.all.forEach { assertEquals(it, RegionSession.writable(it).name) }

    // --- the reply: D3 and review focus 3 -------------------------------------------------------------------------

    @Test fun theLocalReplyIsCopiedAndItsPasskeySentBack() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.SENT), results)
        assertTrue(timers.isEmpty())
        val sent = writes.drop(1)
        assertEquals(RegionWriter.messages(asked, RegionCode.EU_868, passkey), sent.map { it.admin })
        sent.forEach {
            assertEquals(0x0a0b0c0d, it.to)
            assertFalse(it.wantResponse)
            assertEquals(passkey, it.admin.sessionPasskey)
        }
        assertEquals(asked.toBuilder().setRegion(RegionCode.EU_868).build(), setConfigs().single().admin.setConfig.lora)
    }

    @Test fun aReplyFromAnotherNodeIsIgnored() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        val foreignKey = ByteString.copyFrom(byteArrayOf(6, 6, 6))
        session.onReply(RadioEvent.AdminLora(0x99L, asked.toBuilder().setTxPower(30).build(), foreignKey))
        // Nothing happened: still waiting for the local radio.
        assertEquals(1, writes.size)
        assertTrue(results.isEmpty())
        assertEquals(1, timers.size)
        timeout()
        // No local answer in 10 s: the handshake's settings, and no passkey — never the other node's.
        assertEquals(listOf(RegionResult.SENT), results)
        assertEquals(RegionWriter.messages(handshake, RegionCode.EU_868, ByteString.EMPTY), writes.drop(1).map { it.admin })
        writes.forEach { assertTrue(it.admin.sessionPasskey.isEmpty) }
    }

    @Test fun aLocalReplyWithoutLoraFallsBackToTheHandshake() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, null, passkey))
        assertEquals(listOf(RegionResult.SENT), results)
        assertEquals(RegionWriter.messages(handshake, RegionCode.EU_868, passkey), writes.drop(1).map { it.admin })
    }

    @Test fun aHighNodeNumberIsStillTheLocalNode() {
        localNode = 0x89abcdef.toInt()
        session.onSettings(handshake)
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(0x89abcdefL, asked, passkey))
        assertEquals(listOf(RegionResult.SENT), results)
        assertEquals(asked.toBuilder().setRegion(RegionCode.EU_868).build(), setConfigs().single().admin.setConfig.lora)
    }

    // --- results --------------------------------------------------------------------------------------------------

    @Test fun withNoSettingsAtAllNothingIsWritten() {
        session.start("EU_868", done)
        timeout()
        assertEquals(listOf(RegionResult.NO_SETTINGS), results)
        assertEquals(listOf(Write(RegionWriter.getLora(), 0x0a0b0c0d, true)), writes)
    }

    @Test fun aLinkThatIsNotReadyDropsTheWrite() {
        session.onSettings(handshake)
        ready = false
        session.start("EU_868", done)
        ready = true
        localNode = null
        session.start("EU_868", done)
        assertEquals(listOf(RegionResult.DROPPED, RegionResult.DROPPED), results)
        assertTrue(writes.isEmpty())
        assertTrue(timers.isEmpty())
    }

    @Test fun aRefusedRequestDropsTheWrite() {
        session.onSettings(handshake)
        accept = 0
        session.start("EU_868", done)
        assertEquals(listOf(RegionResult.DROPPED), results)
        assertTrue(timers.isEmpty())
        accept = Int.MAX_VALUE
        session.start("EU_868", done)
        assertEquals(1, writes.size)
    }

    @Test fun onlyOneWriteRunsAtATime() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        val second = mutableListOf<RegionResult>()
        session.start("EU_433") { second += it }
        assertEquals(listOf(RegionResult.DROPPED), second)
        assertEquals(1, writes.size)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.SENT), results)
        assertEquals(RegionCode.EU_868, setConfigs().single().admin.setConfig.lora.region)
    }

    @Test fun aWriteTheLinkRefusesPartWayIsDropped() {
        session.onSettings(handshake)
        accept = 2 // the request and begin_edit_settings, then the link refuses
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.DROPPED), results)
        assertTrue(writes.none { it.admin.commitEditSettings })
    }

    /** DROPPED means the radio was not changed: with no room for all three messages, none of them is queued. */
    @Test fun aWriteWithoutRoomForTheWholeTransactionQueuesNoneOfIt() {
        session.onSettings(handshake)
        roomy = false
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.DROPPED), results)
        assertEquals("only the read request left", listOf(AdminMessage.PayloadVariantCase.GET_CONFIG_REQUEST), writes.map { it.admin.payloadVariantCase })
        assertTrue(writes.none { it.admin.beginEditSettings || it.admin.hasSetConfig() || it.admin.commitEditSettings })
        // Positive control: with room, the same transaction goes out whole, and the check saw all three messages.
        roomy = true
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.DROPPED, RegionResult.SENT), results)
        assertEquals(5, writes.size)
        val transaction = listOf(
            AdminMessage.PayloadVariantCase.BEGIN_EDIT_SETTINGS,
            AdminMessage.PayloadVariantCase.SET_CONFIG,
            AdminMessage.PayloadVariantCase.COMMIT_EDIT_SETTINGS,
        )
        assertEquals(listOf(transaction, transaction), roomAsked)
    }

    @Test fun theLinkLostWhileWaitingDropsTheWriteOnce() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        val waiting = timers.single().first
        session.reset()
        assertEquals(listOf(RegionResult.DROPPED), results)
        assertTrue(timers.isEmpty())
        // A late answer or a late timer does nothing more.
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        waiting.run()
        assertEquals(listOf(RegionResult.DROPPED), results)
        assertEquals(1, writes.size)
    }

    @Test fun aLinkThatStoppedBeingReadyBeforeTheReplyWritesNothing() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        ready = false
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.DROPPED), results)
        assertEquals(1, writes.size)
    }

    @Test fun aSecondReplyDoesNotWriteAgain() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(listOf(RegionResult.SENT), results)
        assertEquals(4, writes.size)
    }

    /** Whatever is written, only get_config, begin, set_config(lora) and commit ever leave: no channel, key or owner. */
    @Test fun nothingButTheRegionTransactionIsEverWritten() {
        session.onSettings(handshake)
        session.start("EU_868", done)
        session.onReply(RadioEvent.AdminLora(local, asked, passkey))
        assertEquals(
            listOf(
                AdminMessage.PayloadVariantCase.GET_CONFIG_REQUEST,
                AdminMessage.PayloadVariantCase.BEGIN_EDIT_SETTINGS,
                AdminMessage.PayloadVariantCase.SET_CONFIG,
                AdminMessage.PayloadVariantCase.COMMIT_EDIT_SETTINGS,
            ),
            writes.map { it.admin.payloadVariantCase },
        )
        assertTrue(setConfigs().single().admin.setConfig.hasLora())
    }
}
