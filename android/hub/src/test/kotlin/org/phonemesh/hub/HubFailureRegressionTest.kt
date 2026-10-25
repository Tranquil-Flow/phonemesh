package org.phonemesh.hub

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Compiles on the unmodified baseline: these are behavioral REDs, not missing-API tests. */
class HubFailureRegressionTest {
    @get:Rule val folder = TemporaryFolder()
    private val now = 1_791_000_000_000L

    @Test fun serialExceptionDoesNotEscapeTickOrDiscardAcceptedWork() {
        val radio = object : Radio {
            override fun broadcast(port: Int, payload: ByteArray) { throw IOException("ambiguous write") }
        }
        val hub = Hub(Mailbox(folder.root, 30), radio).also { it.clock = { now } }
        assertEquals(Hub.Sent.STORED, hub.fromPhone("phone", 301, Packets.message(1)))
        try { hub.tick() } catch (e: IOException) {
            fail("serial exception escaped tick; accepted work must remain recoverable")
        }
        assertEquals("accepted work must remain recoverable", 1, hub.waiting().values.sum())
    }

    @Test fun failedAppendDoesNotPublishMailboxOrDuplicateState() {
        val box = Mailbox(folder.root, 30)
        val path = File(folder.root, Mailbox.day(now) + ".mail")
        assertTrue(path.mkdir()) // actual file-open failure, without permissions/root assumptions
        try { box.put(301, Packets.message(1), now); fail("append should fail") }
        catch (_: IOException) { }
        assertEquals("failed append published phantom mail", 0, box.size())
        assertEquals("failed append consumed a sequence", 0L, box.latest())
        assertTrue(path.delete()) // only this test's empty temporary fixture directory
        assertNotNull("failed append poisoned duplicate state", box.put(301, Packets.message(1), now))
        assertEquals(1, Mailbox(folder.root, 30).size())
    }

    @Test fun truncatedTailMustNotHideLaterSuccessfulAppends() {
        val box = Mailbox(folder.root, 30)
        box.put(301, Packets.message(1), now)
        File(folder.root, Mailbox.day(now) + ".mail").appendBytes(byteArrayOf(0, 0, 0))
        val recovered = Mailbox(folder.root, 30)
        recovered.put(301, Packets.message(2), now)
        assertEquals("old partial tail hid a later successful append", 2, Mailbox(folder.root, 30).size())
    }
}
