package org.phonemesh.ble

import org.junit.Assert.*
import org.junit.Test
import org.meshtastic.proto.MeshProtos

class LifecycleBoundaryTest {
    @Test fun retiredGattIdentityCannotMutateReplacement() {
        val fence=SessionFence<Any>()
        val old=Any(); val replacement=Any()
        fence.replace(old); assertTrue(fence.accepts(old))
        fence.replace(replacement); assertFalse(fence.accepts(old)); assertTrue(fence.accepts(replacement))
        fence.replace(null); assertFalse(fence.accepts(replacement))
    }
    @Test fun timeoutClearsQueueRatherThanStartingAnotherGattOperation() {
        val timers=mutableListOf<Runnable>(); val starts=mutableListOf<String>(); val failures=mutableListOf<String>()
        val queue=SafeGattQueue({task,_->timers.add(task)},{timers.remove(it)},{failures.add(it)})
        queue.enqueue("first") { starts.add("first"); true }
        queue.enqueue("second") { starts.add("second"); true }
        timers.single().run()
        assertEquals(listOf("first"),starts)
        assertEquals(1,failures.size)
        queue.complete("first")
        assertEquals(listOf("first"),starts)
    }
    @Test fun roomPromisesThatManyOperationsAreAccepted() {
        val timers=mutableListOf<Runnable>(); val failures=mutableListOf<String>()
        val queue=SafeGattQueue({task,_->timers.add(task)},{timers.remove(it)},{failures.add(it)},maxPending=4)
        assertTrue(queue.room(4)); assertFalse(queue.room(5))
        assertTrue(queue.enqueue("active") { true })
        assertTrue(queue.room(3)); assertFalse(queue.room(4))
        repeat(3) { assertTrue(queue.enqueue("write$it") { true }) }
        assertFalse(queue.room(1))
        assertFalse(queue.enqueue("over") { true })
        queue.complete("active")
        assertTrue(queue.room(1)); assertFalse(queue.room(2))
        assertTrue(failures.isEmpty())
    }
    @Test fun startFailureClearsQueuedWorkAndSuccessRemainsSerialized() {
        val timers=mutableListOf<Runnable>(); val starts=mutableListOf<String>(); val failures=mutableListOf<String>()
        val queue=SafeGattQueue({task,_->timers.add(task)},{timers.remove(it)},{failures.add(it)})
        queue.enqueue("first") { starts.add("first"); true }
        queue.enqueue("bad") { starts.add("bad"); false }
        queue.enqueue("third") { starts.add("third"); true }
        assertEquals(listOf("first"),starts)
        queue.complete("first")
        assertEquals(listOf("first","bad"),starts)
        assertEquals(1,failures.size)
        assertTrue(timers.isEmpty())
    }
    @Test fun directMessageRequestsAckButBroadcastDoesNot() {
        val direct=MeshProtos.ToRadio.parseFrom(MeshtasticProtocol.buildTextToRadio("hello","!12345678",0))
        val broadcast=MeshProtos.ToRadio.parseFrom(MeshtasticProtocol.buildTextToRadio("hello","^all",0))
        assertTrue(direct.packet.wantAck)
        assertFalse(broadcast.packet.wantAck)
    }
}
