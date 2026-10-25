package org.phonemesh.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.SocketFactory

/**
 * Accounts plan step 16: late Android callbacks (join, link change, lost, unavailable) can never publish a hub after the
 * link was left or replaced, and leaving closes the published client's sockets. WifiHubLink routes every callback here.
 */
class HubSessionTest {
    private val pin = HubCard.pinOf(ByteArray(91) { it.toByte() })
    private fun client() = HubClient("127.0.0.1", 9, pin, SocketFactory.getDefault())

    @Test fun aCurrentCallbackPublishesAndALostOneClears() {
        val session = HubSession()
        val gen = session.begin()
        assertNull(session.wire())
        assertTrue(session.available(gen) { client() })
        assertNotNull(session.wire())
        assertTrue(session.lost(gen))
        assertNull(session.wire())
    }

    @Test fun aLateAvailableAfterLeavingPublishesNothingAndBuildsNothing() {
        val session = HubSession()
        val gen = session.begin()
        session.end()
        assertFalse(session.available(gen) { throw AssertionError("a stale callback must not even build a client") })
        assertNull(session.wire())
    }

    @Test fun aLateCallbackOfTheOldCardCannotTouchTheNewJoin() {
        val session = HubSession()
        val old = session.begin()
        val fresh = session.begin()
        assertFalse(session.available(old) { client() })
        assertTrue(session.available(fresh) { client() })
        val published = session.wire()
        assertFalse("a stale lost must not clear the new hub", session.lost(old))
        assertFalse("a stale unavailable must not clear the new hub", session.lost(old))
        assertFalse(session.available(old) { client() })
        assertTrue(published === session.wire())
    }

    @Test fun leavingClosesThePublishedClientSoItCanNeverBeUsedAgain() {
        val session = HubSession()
        val gen = session.begin()
        val made = client()
        assertTrue(session.available(gen) { made })
        session.end()
        assertNull(session.wire())
        val failed = runCatching { made.send(301, ByteArray(10)) }.exceptionOrNull()
        assertTrue("$failed", failed is IOException)
    }

    @Test fun aSecondAvailableForTheSameJoinClosesTheClientItReplaces() {
        val session = HubSession()
        val gen = session.begin()
        val first = client()
        val second = client()
        assertTrue(session.available(gen) { first })
        assertTrue(session.available(gen) { second })
        assertTrue(runCatching { first.send(301, ByteArray(10)) }.exceptionOrNull() is IOException)
        assertTrue(second === session.wire())
        assertEquals(second, session.wire())
    }
}
