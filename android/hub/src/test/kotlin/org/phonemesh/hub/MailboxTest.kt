package org.phonemesh.hub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.phonemesh.hub.Packets.code
import org.phonemesh.hub.Packets.hello
import org.phonemesh.hub.Packets.message
import org.phonemesh.hub.Packets.shared
import kotlin.concurrent.thread

class MailboxTest {
    @get:Rule val folder = TemporaryFolder()
    private val day = Mailbox.DAY
    private val start = 1_791_000_000_000L

    @Test fun mailIsServedByCodeAndHellosAndSharedPostsToWhoeverAsks() {
        val box = Mailbox(folder.root, 30)
        box.put(301, message(1), start); box.put(301, message(2), start); box.put(301, hello(3), start); box.put(303, shared("salut"), start)
        val mine = box.after(0, setOf(Mailbox.hex(code(2))), hellos = false, shared = false)
        assertEquals(1, mine.size)
        assertArrayEquals(message(2), mine[0].bytes)
        assertEquals(listOf(Mailbox.Kind.HELLO), box.after(0, emptySet(), hellos = true, shared = false).map { it.kind })
        assertEquals(listOf(Mailbox.Kind.SHARED), box.after(0, emptySet(), hellos = false, shared = true).map { it.kind })
        assertTrue("nothing after the newest", box.after(box.latest(), setOf(Mailbox.hex(code(2))), true, true).isEmpty())
    }

    @Test fun aSecondCopyIsNotStoredTwice() {
        val box = Mailbox(folder.root, 30)
        assertTrue(box.put(301, message(1), start) != null)
        assertNull("a relay's copy", box.put(301, message(1), start + 5000))
        assertEquals(1, box.size())
    }

    @Test fun malformedPacketsAreIgnored() {
        val box = Mailbox(folder.root, 30)
        assertNull("too short", box.put(301, ByteArray(10), start))
        assertNull("too long", box.put(301, message(1, 229), start))
        assertNull("wrong version byte", box.put(301, message(1).also { it[0] = 0x55 }, start))
        assertNull("not a shared-chat post", box.put(303, ByteArray(30), start))
        assertNull("not a mail port", box.put(300, message(1), start))
        assertNull("acks are not mail", box.put(302, message(1), start))
        assertEquals(0, box.size())
    }

    @Test fun mailSurvivesARestartAndExpires() {
        Mailbox(folder.root, 30).apply {
            put(301, message(1), start)
            put(301, message(2), start + 10 * day)
        }
        val again = Mailbox(folder.root, 30)
        assertEquals(2, again.size())
        assertEquals(2, again.latest())
        assertTrue("seq keeps rising", again.put(301, message(3), start + 10 * day)!!.seq == 3L)
        again.expire(start + 31 * day)
        assertEquals(2, again.size())
        assertEquals("the first day's file is gone", 1, folder.root.listFiles()!!.count { it.name.endsWith(".mail") })
        assertTrue(Mailbox(folder.root, 30).after(0, setOf(Mailbox.hex(code(1))), false, false).isEmpty())
    }

    @Test fun aRecordCutShortByACrashLosesOnlyItself() {
        Mailbox(folder.root, 30).apply { put(301, message(1), start); put(301, message(2), start) }
        val file = folder.root.listFiles()!!.single { it.name.endsWith(".mail") }
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 5))
        assertEquals(1, Mailbox(folder.root, 30).size())
    }

    @Test fun aLongPollWakesWhenMailArrives() {
        val box = Mailbox(folder.root, 30)
        val wanted = setOf(Mailbox.hex(code(9)))
        var got: List<Mailbox.Record> = emptyList()
        val waiting = thread { got = box.await(0, wanted, false, false, 10_000) }
        Thread.sleep(200)
        box.put(301, message(8), start)
        Thread.sleep(100)
        assertTrue("other mail does not end the wait", waiting.isAlive)
        val began = System.currentTimeMillis()
        box.put(301, message(9), start)
        waiting.join(5000)
        assertTrue(System.currentTimeMillis() - began < 2000)
        assertEquals(1, got.size)
        assertTrue("an empty wait ends on time", box.await(box.latest(), wanted, false, false, 300).isEmpty())
    }
}
