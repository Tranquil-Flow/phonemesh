package org.phonemesh.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.AccountId

class StartTest {
    @Test fun afterAQrScanBothPhonesShareKeysAndEitherMayWriteFirst() {
        repeat(20) {
            val (a, b) = Phone.qrPair()
            assertArrayEquals(a.state.rk, b.state.rk)
            assertArrayEquals(a.state.cks, b.state.ckr)
            assertArrayEquals(a.state.ckr, b.state.cks)
            assertArrayEquals(a.state.nhk, b.state.nhk)
            assertNotEquals(a.state.iAmL, b.state.iAmL)
            assertEquals("L is the smaller account id", AccountId.of(a.keys.identity) < AccountId.of(b.keys.identity), a.state.iAmL)
            // Both write before either hears the other.
            val fromA = a.send("salut")
            val fromB = b.send("bonjour")
            assertEquals("salut", b.receive(fromA.packet))
            assertEquals("bonjour", a.receive(fromB.packet))
        }
    }

    @Test fun swappedRolesGiveTheSameSplit() {
        val a = Phone("A"); val b = Phone("B")
        val ab = checkNotNull(Start.qr(a.keys, b.card))
        val ba = checkNotNull(Start.qr(b.keys, a.card))
        val (l, h) = if (ab.iAmL) ab to ba else ba to ab
        assertTrue(l.iAmL && !h.iAmL && l.mayStep && !h.mayStep)
    }

    @Test fun ownCardIsNotAContact() {
        val a = Phone("A")
        assertNull(Start.qr(a.keys, a.card))
    }

    @Test fun aHelloOpensOnlyForTheLinksOwnerOnItsDay() {
        val owner = Phone("Owner"); val opener = Phone("Opener"); val stranger = Phone("Stranger")
        val day = 20_375L
        val opened = checkNotNull(Start.link(opener.keys, "Opener", 3, owner.card, KeyPairBytes.generate(), day))
        assertTrue("one packet: ${opened.hello.size}", opened.hello.size <= 134)
        assertNull("another phone", Start.hello(stranger.keys, opened.hello, day))
        assertNull("two days later", Start.hello(owner.keys, opened.hello, day + 2))
        assertNull("damaged", Start.hello(owner.keys, opened.hello.copyOf().also { it[50] = (it[50] + 1).toByte() }, day))
        for (today in day - 1..day + 1) {
            val got = checkNotNull(Start.hello(owner.keys, opened.hello, today))
            assertEquals("Opener", got.name)
            assertEquals(3, got.colour)
            assertArrayEquals(opener.keys.identity, got.identity)
        }
    }

    @Test fun aLinkConversationRunsBothWays() {
        val owner = Phone("Owner"); val opener = Phone("Opener")
        val opened = checkNotNull(Start.link(opener.keys, "Opener", 3, owner.card, KeyPairBytes.generate(), 5))
        opener.state = opened.state
        owner.state = checkNotNull(Start.hello(owner.keys, opened.hello, 5)).state
        assertEquals("ça va ?", owner.receive(opener.send("ça va ?").packet))
        val reply = owner.send("oui")
        assertTrue("the owner cannot step first", !reply.stepped)
        assertEquals("oui", opener.receive(reply.packet))
        val stepped = opener.send("super")
        assertTrue("the opener steps after hearing back", stepped.stepped && stepped.withKey)
        assertEquals("super", owner.receive(stepped.packet))
        val answer = owner.send("à demain")
        assertTrue(answer.stepped)
        assertEquals("à demain", opener.receive(answer.packet))
    }

    @Test fun helloCodesChangeEveryDay() {
        val owner = Phone("Owner")
        assertNotEquals(Start.helloCode(owner.keys.prekey, 1).toList(), Start.helloCode(owner.keys.prekey, 2).toList())
    }
}
