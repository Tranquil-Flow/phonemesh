package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums

class DeliveryBoundaryTest {
    @Test fun staggeredPendingSendsExpireAtTheirOwnDeadline() {
        var time=0L
        val tracker=DeliveryTracker({time})
        assertNull(tracker.nextExpiryDelay())
        tracker.track(1,peer,local)
        time=1_000L
        tracker.track(2,peer,local)
        time=90_000L
        assertEquals(1L,tracker.expire().single().packetId)
        assertEquals(1_000L,tracker.nextExpiryDelay())
        time=91_000L
        assertEquals(2L,tracker.expire().single().packetId)
        assertNull(tracker.nextExpiryDelay())
    }

    @Test fun sessionLostBeforeTrackStillProducesAnExplicitFailure() {
        val tracker=DeliveryTracker({0L})
        val emission=tracker.rejectUnsubmitted(42L,"session changed before write").single()
        assertEquals(42L,emission.packetId)
        assertEquals(DeliveryState.FAILED,emission.state)
        assertEquals(0,tracker.size)
    }

    private val local=0x11111111L
    private val peer=0x22222222L
    private val foreign=0x33333333L

    @Test fun duplicateAndInvalidIdsCannotTakeOverCorrelation() {
        val tracker=DeliveryTracker({0L})
        assertFalse(tracker.track(0L,peer,local))
        assertFalse(tracker.track(0x1_0000_0000L,peer,local))
        assertTrue(tracker.track(42L,peer,local))
        assertFalse(tracker.track(42L,foreign,local))
        assertTrue(tracker.onRouting(RoutingOutcome(foreign,local,42L,null),local).isEmpty())
        assertEquals(DeliveryState.ACKNOWLEDGED,tracker.onRouting(RoutingOutcome(peer,local,42L,null),local).single().state)
    }

    @Test fun unrelatedErrorCannotTerminateOurPendingSend() {
        val tracker=DeliveryTracker({0L})
        tracker.track(42L,peer,local)
        assertTrue(tracker.onRouting(RoutingOutcome(foreign,local,42L,"NO_ROUTE"),local).isEmpty())
        assertEquals(DeliveryState.ACKNOWLEDGED,tracker.onRouting(RoutingOutcome(peer,local,42L,null),local).single().state)
    }

    @Test fun delayedExpiryTickCannotAdmitLateAcknowledgement() {
        var time=0L
        val tracker=DeliveryTracker({time})
        tracker.track(42L,peer,local)
        time=DeliveryTracker.TIMEOUT_MS
        assertEquals(DeliveryState.UNCONFIRMED,tracker.onRouting(RoutingOutcome(peer,local,42L,null),local).single().state)
        assertEquals(0,tracker.size)
    }

    @Test fun delayedExpiryTickCannotRestoreQueueStatus() {
        var time=0L
        val tracker=DeliveryTracker({time})
        tracker.track(42L,peer,local)
        time=DeliveryTracker.TIMEOUT_MS
        assertEquals(DeliveryState.UNCONFIRMED,tracker.onQueued(42L).single().state)
    }

    @Test fun onlyExplicitErrorReasonCanBeAnAckOrNak() {
        fun parse(routing:MeshProtos.Routing):RadioEvent? {
            val decoded=MeshProtos.Data.newBuilder().setPortnum(Portnums.PortNum.ROUTING_APP)
                .setRequestId(42).setPayload(routing.toByteString())
            val packet=MeshProtos.MeshPacket.newBuilder().setFrom(peer.toInt()).setTo(local.toInt()).setDecoded(decoded)
            return MeshtasticProtocol.parseFromRadio(MeshProtos.FromRadio.newBuilder().setPacket(packet).build().toByteArray())
        }
        assertNull(parse(MeshProtos.Routing.getDefaultInstance()))
        assertNull(parse(MeshProtos.Routing.newBuilder().setRouteRequest(MeshProtos.RouteDiscovery.getDefaultInstance()).build()))
        val ack=parse(MeshProtos.Routing.newBuilder().setErrorReason(MeshProtos.Routing.Error.NONE).build()) as RadioEvent.Routing
        assertNull(ack.error)
        val nak=parse(MeshProtos.Routing.newBuilder().setErrorReason(MeshProtos.Routing.Error.NO_ROUTE).build()) as RadioEvent.Routing
        assertEquals("NO_ROUTE",nak.error)
    }
}
