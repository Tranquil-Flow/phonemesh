package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * NEW-API tests (GattStreams, GattFrame, HubGatt) of the phone's framing, MTU fragmentation, one-write-at-a-time rule,
 * credit backpressure, deadlines, rejected writes and late callbacks. The "hub" is the test itself, feeding frames by hand.
 * NOT RUN when staged.
 */
class GattStreamsTest {
    private class Harness(var frame: Int = 509) {
        val sent = CopyOnWriteArrayList<GattFrame>()
        val dead = CopyOnWriteArrayList<String>()
        @Volatile var accept = true
        @Volatile var autoAck = true
        private val acks = Executors.newSingleThreadExecutor { Thread(it, "test-ack").apply { isDaemon = true } }
        private val port = object : GattPort {
            override fun maxFrame() = frame

            override fun write(frame: ByteArray): Boolean {
                if (!accept) return false
                sent += GattFrame.decode(frame)!!
                if (autoAck) acks.execute { streams.written(true) }
                return true
            }
        }
        val streams: GattStreams = GattStreams(port) { dead += it }

        /** Returns when every acknowledgement already due has been delivered. */
        fun settle() { acks.submit {}.get(3, TimeUnit.SECONDS) }

        fun data() = sent.filter { it.type == HubGatt.DATA }
    }

    private fun waitUntil(ms: Long = 3_000, condition: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail("timed out waiting")
            Thread.sleep(5)
        }
    }

    private fun open(h: Harness, id: Int, ms: Int = 5_000): HubStream {
        val got = AtomicReference<HubStream?>()
        val failure = AtomicReference<Throwable?>()
        val t = thread { try { got.set(h.streams.open(ms)) } catch (e: Throwable) { failure.set(e) } }
        waitUntil { h.sent.any { it.type == HubGatt.OPEN && it.stream == id } }
        h.streams.onFrame(GattFrame.opened(id).encode())
        t.join(5_000)
        failure.get()?.let { throw it }
        if (h.autoAck) h.settle()
        return got.get()!!
    }

    @Test fun openAsksForOneStreamAndWaitsForTheHub() {
        val h = Harness()
        val s = open(h, 1)
        val first = h.sent.first()
        assertEquals(HubGatt.OPEN, first.type)
        assertEquals(1, first.stream)
        s.abort()
    }

    @Test fun aSilentHubEndsOpenAtItsDeadline() {
        val h = Harness()
        val start = System.nanoTime()
        val failure = runCatching { h.streams.open(300) }.exceptionOrNull()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$failure", failure is IOException)
        assertTrue("took $ms ms", ms in 250..3_000)
        // The lane is given back: the next open asks again, with a new number.
        val next = thread { runCatching { h.streams.open(300) } }
        waitUntil { h.sent.count { it.type == HubGatt.OPEN } == 2 }
        assertEquals(2, h.sent.last { it.type == HubGatt.OPEN }.stream)
        next.join(3_000)
    }

    @Test fun writesFitTheShortestMtuAndGoOneAtATime() {
        for (size in listOf(1, 16, 17, 18, 34, 35, 100)) {
            val h = Harness(frame = 20) // ATT MTU 23
            h.autoAck = false
            val s = open(h, 1)
            h.streams.written(true) // the OPEN write finished
            val payload = ByteArray(size) { (it * 7).toByte() }
            s.output.write(payload)
            assertEquals("only one write outstanding", 1, h.data().size)
            while (h.data().sumOf { it.payload.size } < size) h.streams.written(true)
            assertEquals((size + 16) / 17, h.data().size)
            assertTrue(h.data().all { it.payload.size <= 17 && it.encode().size <= 20 })
            assertEquals((0 until h.data().size).toList(), h.data().map { it.seq })
            assertTrue(payload.contentEquals(h.data().flatMap { it.payload.toList() }.toByteArray()))
        }
    }

    @Test fun creditStopsTheWriterAndAGrantResumesIt() {
        val h = Harness()
        val s = open(h, 1)
        val done = AtomicBoolean(false)
        val writer = thread { s.output.write(ByteArray(HubGatt.WINDOW + 100)); done.set(true) }
        waitUntil { h.data().sumOf { it.payload.size } == HubGatt.WINDOW }
        Thread.sleep(150)
        assertFalse("the window is full: the writer must wait", done.get())
        h.streams.onFrame(GattFrame.credit(1, 100).encode())
        writer.join(3_000)
        assertTrue(done.get())
        assertEquals(HubGatt.WINDOW + 100, h.data().sumOf { it.payload.size })
    }

    @Test fun inboundBytesKeepTheirOrderAndGiveCreditBack() {
        val h = Harness()
        val s = open(h, 1)
        h.streams.onFrame(GattFrame.data(1, 0, "ab".toByteArray()).encode())
        h.streams.onFrame(GattFrame.data(1, 1, "cd".toByteArray()).encode())
        val buffer = ByteArray(10)
        val n = s.input.read(buffer)
        assertEquals("abcd", String(buffer, 0, n))
        waitUntil { h.sent.any { it.type == HubGatt.CREDIT && it.credit == 4 } }
        h.streams.onFrame(GattFrame.close(1).encode())
        assertEquals(-1, s.input.read(buffer))
    }

    @Test fun aGapInTheSequenceBreaksOnlyThatStream() {
        val h = Harness()
        val s = open(h, 1)
        h.streams.onFrame(GattFrame.data(1, 1, byteArrayOf(1)).encode())
        val failure = runCatching { s.input.read(ByteArray(4)) }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        waitUntil { h.sent.any { it.type == HubGatt.RESET && it.stream == 1 && it.payload[0].toInt() == HubGatt.RESET_PROTOCOL } }
        assertFalse(h.streams.isDead)
        open(h, 2).abort()
    }

    @Test fun moreThanTheWindowIsRefusedNotBuffered() {
        val h = Harness()
        val s = open(h, 1)
        for (i in 0 until 17) h.streams.onFrame(GattFrame.data(1, i, ByteArray(509)).encode()) // 17 x 509 > 8192
        waitUntil { h.sent.any { it.type == HubGatt.RESET && it.payload[0].toInt() == HubGatt.RESET_FLOW } }
        assertTrue(runCatching { s.input.read(ByteArray(4)) }.exceptionOrNull() is IOException)
    }

    @Test fun aGrantBeyondTheWindowIsRefused() {
        val h = Harness()
        val s = open(h, 1)
        h.streams.onFrame(GattFrame.credit(1, 1).encode()) // the window is already full from the start
        waitUntil { h.sent.any { it.type == HubGatt.RESET && it.payload[0].toInt() == HubGatt.RESET_FLOW } }
        assertTrue(runCatching { s.output.write(byteArrayOf(1)) }.exceptionOrNull() is IOException)
    }

    @Test fun aRefusedWriteEndsTheLinkAndFailsEveryStream() {
        val h = Harness()
        val s = open(h, 1)
        h.accept = false
        s.output.write(byteArrayOf(1))
        assertTrue(h.streams.isDead)
        assertEquals(1, h.dead.size)
        assertTrue(runCatching { s.input.read(ByteArray(1)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { s.output.write(byteArrayOf(2)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { h.streams.open(100) }.exceptionOrNull() is IOException)
    }

    @Test fun aWriteThatFailsLaterEndsTheLinkToo() {
        val h = Harness()
        h.autoAck = false
        val s = open(h, 1)
        h.streams.written(true)
        s.output.write(byteArrayOf(1))
        h.streams.written(false)
        assertTrue(h.streams.isDead)
        assertTrue(runCatching { s.input.read(ByteArray(1)) }.exceptionOrNull() is IOException)
    }

    @Test fun aDeadLinkIgnoresEveryLaterCallback() {
        val h = Harness()
        val s = open(h, 1)
        h.streams.linkClosed("gone")
        h.streams.linkClosed("gone again")
        h.streams.onFrame(GattFrame.opened(1).encode())
        h.streams.onFrame(GattFrame.data(1, 0, byteArrayOf(1)).encode())
        h.streams.onFrame(byteArrayOf(9, 9))
        h.streams.written(true)
        assertEquals(listOf("gone"), h.dead.toList())
        assertTrue(runCatching { s.input.read(ByteArray(1)) }.exceptionOrNull() is IOException)
    }

    @Test fun aFrameThatMakesNoSenseEndsTheLink() {
        val h = Harness()
        open(h, 1)
        h.streams.onFrame(byteArrayOf(3, 1))
        assertTrue(h.streams.isDead)
    }

    @Test fun aDrippingHubCannotOutlastTheTotalDeadline() {
        val h = Harness()
        val s = open(h, 1, ms = 500)
        val stop = AtomicBoolean(false)
        thread(isDaemon = true) {
            var seq = 0
            while (!stop.get()) { h.streams.onFrame(GattFrame.data(1, seq++ and 0xFF, byteArrayOf(1)).encode()); Thread.sleep(100) }
        }
        val start = System.nanoTime()
        val failure = runCatching { while (true) s.input.read(ByteArray(8)) }.exceptionOrNull()
        stop.set(true)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("$failure", failure is IOException)
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test fun abortWakesAReaderAtOnce() {
        val h = Harness()
        val s = open(h, 1)
        val failure = AtomicReference<Throwable?>()
        val reader = thread { failure.set(runCatching { s.input.read(ByteArray(1)) }.exceptionOrNull()) }
        Thread.sleep(100)
        s.abort()
        reader.join(2_000)
        assertTrue("${failure.get()}", failure.get() is IOException)
    }

    @Test fun framesOfAClosedStreamAreIgnoredAndItsNumberIsNotReusedAtOnce() {
        val h = Harness()
        val s1 = open(h, 1)
        s1.abort()
        waitUntil { h.sent.any { it.type == HubGatt.CLOSE && it.stream == 1 } }
        h.streams.onFrame(GattFrame.data(1, 0, byteArrayOf(1)).encode()) // a late notification of the old stream
        h.streams.onFrame(GattFrame.opened(1).encode())
        h.settle()
        assertEquals(0, h.sent.count { it.type == HubGatt.RESET })
        val s2 = open(h, 2) // the second stream gets a new number, so stale frames of the first can never reach it
        s2.abort()
    }

    @Test fun onlyTwoStreamsShareALink() {
        val h = Harness()
        val a = open(h, 1)
        val b = open(h, 2)
        val failure = runCatching { h.streams.open(200) }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
        a.abort(); b.abort()
    }
}
