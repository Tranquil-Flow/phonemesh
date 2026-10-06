package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums

class DeliveryTrackingTest {
    private val local = 0x11111111L
    private val peer = 0x22222222L
    private val other = 0x33333333L

    private fun tracker(maxPending: Int = 8) = DeliveryTracker(now = { 0L }, maxPending = maxPending)

    private fun routingPacket(from: Long, to: Long, requestId: Long, error: Int? = null): ByteArray {
        val routing = MeshProtos.Routing.newBuilder()
        routing.errorReason = if (error != null) MeshProtos.Routing.Error.forNumber(error) else MeshProtos.Routing.Error.NONE
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ROUTING_APP)
            .setPayload(routing.build().toByteString())
            .setRequestId(requestId.toInt())
            .build()
        return MeshProtos.MeshPacket.newBuilder()
            .setFrom(from.toInt())
            .setTo(to.toInt())
            .setId(requestId.toInt())
            .setDecoded(data)
            .build()
            .toByteArray()
    }

    private fun fromRadioFor(vararg packets: ByteArray): ByteArray {
        val builder = MeshProtos.FromRadio.newBuilder()
        packets.forEach { builder.packet = MeshProtos.MeshPacket.parseFrom(it) }
        return builder.build().toByteArray()
    }

    // ---- protobuf / correlation seam ----

    @Test fun routingAppPayloadIsParsedSafely() {
        val bytes = routingPacket(peer, local, 42L)
        val decoded = MeshProtos.MeshPacket.parseFrom(bytes).decoded
        assertEquals(Portnums.PortNum.ROUTING_APP, decoded.portnum)
        val event = MeshtasticProtocol.parseFromRadio(fromRadioFor(bytes))
        assertTrue(event is RadioEvent.Routing)
        val routing = event as RadioEvent.Routing
        assertEquals(peer, routing.from)
        assertEquals(local, routing.to)
        assertEquals(42L, routing.requestId)
        assertNull(routing.error)
    }

    @Test fun routingErrorReasonIsCarriedAsText() {
        val bytes = routingPacket(peer, local, 7L, error = MeshProtos.Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY.number)
        val routing = MeshtasticProtocol.parseFromRadio(fromRadioFor(bytes)) as RadioEvent.Routing
        assertEquals("PKI_SEND_FAIL_PUBLIC_KEY", routing.error)
    }

    @Test fun nonRoutingAndTruncatedPayloadsProduceNoRoutingEvent() {
        assertNull(MeshtasticProtocol.parseFromRadio(fromRadioFor(routingPacket(peer, local, 1L)).copyOf(6)))
        val notRouting = MeshProtos.MeshPacket.parseFrom(routingPacket(peer, local, 1L))
            .toBuilder().setDecoded(MeshProtos.Data.newBuilder().setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP))
            .build().toByteArray()
        assertNull(MeshtasticProtocol.parseFromRadio(fromRadioFor(notRouting)))
    }

    // ---- tracker semantics ----

    @Test fun targetAckAcknowledgesAndIsTerminal() {
        val t = tracker()
        assertTrue(t.track(0x1L, peer, local))
        assertEquals(listOf(DeliveryTracker.Emission(0x1L, DeliveryState.QUEUED, "Radio accepted packet for queueing; still not a recipient ACK")), t.onQueued(0x1L))
        val emissions = t.onRouting(RoutingOutcome(from = peer, to = local, requestId = 0x1L, error = null), local)
        assertEquals(1, emissions.size)
        assertEquals(DeliveryState.ACKNOWLEDGED, emissions.single().state)
        assertEquals(0x1L, emissions.single().packetId)
        assertTrue(emissions.single().detail.contains("!22222222"))
        assertEquals(0, t.size)
        assertTrue(t.onRouting(RoutingOutcome(peer, local, 0x1L, null), local).isEmpty())
    }

    @Test fun localOrIntermediateAckIsNotDeliveryAndStaysPending() {
        val t = tracker()
        t.track(0x2L, peer, local)
        t.onQueued(0x2L)
        val fromIntermediate = t.onRouting(RoutingOutcome(other, local, 0x2L, null), local)
        assertTrue(fromIntermediate.isEmpty())
        assertEquals(1, t.size)
    }

    @Test fun foreignOrBroadcastRoutingCannotClaimDelivery() {
        val t = tracker()
        t.track(0x3L, peer, local)
        assertTrue(t.onRouting(RoutingOutcome(peer, 0x44444444L, 0x3L, null), local).isEmpty())
        assertTrue(t.onRouting(RoutingOutcome(peer, local, 0x9999L, null), local).isEmpty())
        assertEquals(1, t.size)
    }

    @Test fun errorReasonIsObservableAsFailure() {
        val t = tracker()
        t.track(0x4L, peer, local)
        t.onQueued(0x4L)
        val emissions = t.onRouting(RoutingOutcome(peer, local, 0x4L, "PKI_SEND_FAIL_PUBLIC_KEY"), local)
        assertEquals(DeliveryState.FAILED, emissions.single().state)
        assertTrue(emissions.single().detail.contains("PKI_SEND_FAIL_PUBLIC_KEY"))
        assertEquals(0, t.size)
    }

    @Test fun pendingMapIsBoundedAndOverflowIsObservable() {
        val t = tracker(maxPending = 2)
        assertTrue(t.track(1L, peer, local))
        assertTrue(t.track(2L, peer, local))
        assertFalse(t.track(3L, peer, local))
        // Untracked ids are inert, including the rejected third send.
        assertTrue(t.onQueued(3L).isEmpty())
        assertTrue(t.onRouting(RoutingOutcome(peer, local, 3L, null), local).isEmpty())
        assertEquals(2, t.size)
    }

    @Test fun radioQueueRejectionIsFailureAndUntrackedReportsAreInert() {
        val t = tracker()
        t.track(0x6L, peer, local)
        val emissions = t.onQueueRejected(0x6L, "res=1")
        assertEquals(DeliveryState.FAILED, emissions.single().state)
        assertTrue(t.onQueued(0x6L).isEmpty())
        assertTrue(t.onQueueRejected(0x7L, "res=1").isEmpty())
        assertEquals(0, t.size)
    }

    @Test fun timeoutBeyondBoundIsUnconfirmed() {
        var now = 0L
        val t = DeliveryTracker(now = { now })
        t.track(0x5L, peer, local)
        now = DeliveryTracker.TIMEOUT_MS - 1
        assertTrue(t.expire().isEmpty())
        now = DeliveryTracker.TIMEOUT_MS
        val emissions = t.expire()
        assertEquals(DeliveryState.UNCONFIRMED, emissions.single().state)
        assertEquals(0x5L, emissions.single().packetId)
        assertEquals(0, t.size)
    }

    @Test fun disconnectMarksEveryPendingPacketUnconfirmed() {
        val t = tracker()
        t.track(1L, peer, local)
        t.track(2L, peer, local)
        val emissions = t.failPending("BLE radio disconnected")
        assertEquals(2, emissions.size)
        assertTrue(emissions.all { it.state == DeliveryState.UNCONFIRMED })
        assertTrue(emissions.all { it.detail.contains("BLE radio disconnected") })
        assertEquals(0, t.size)
    }

    @Test fun trackerStoresNoMessageText() {
        val fields = DeliveryTracker.Pending::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("text", ignoreCase = true) || it.contains("payload", ignoreCase = true) })
    }

    @Test fun sendReturnsUnsignedPacketIdAndDoesNotRequestAckForBroadcast() {
        val toRadio = MeshProtos.ToRadio.parseFrom(
            MeshtasticProtocol.buildTextToRadio("hi", "!22222222", 0, packetId = 0x8000_0001.toInt()),
        )!!
        assertEquals(0x8000_0001L, toRadio.packet.id.toLong() and 0xffffffffL)
        val broadcast = MeshProtos.ToRadio.parseFrom(MeshtasticProtocol.buildTextToRadio("hi", "^all", 0))!!
        assertFalse(broadcast.packet.wantAck)
        assertEquals(MeshtasticProtocol.BROADCAST_NUM, broadcast.packet.to.toLong() and 0xffffffffL)
    }
}
