package org.phonemesh.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.AccountId
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.Vectors

class StartTest {
    @Test fun afterAQrScanBothPhonesShareKeysAndEitherMayWriteFirst() {
        repeat(20) {
            val (a, b) = Phone.qrPair()
            assertArrayEquals(a.state.rk, b.state.rk)
            assertArrayEquals(a.state.cks, b.state.ckr)
            assertArrayEquals(a.state.ckr, b.state.cks)
            assertNotEquals(a.state.iAmL, b.state.iAmL)
            assertEquals("L is the smaller account id", AccountId.of(a.keys.identity) < AccountId.of(b.keys.identity), a.state.iAmL)
            assertEquals("L steps first", a.state.iAmL, a.state.mayStep)
            val fromA = a.send("salut")
            val fromB = b.send("bonjour")
            assertEquals("salut", b.receive(fromA.packet))
            assertEquals("bonjour", a.receive(fromB.packet))
        }
    }

    /** Review F1: a second scan between the same people must not give the same keys again. */
    @Test fun aSecondQrScanGivesNewKeys() {
        val a = Phone("A"); val b = Phone("B")
        fun scan(): Pair<SessionState, SessionState> {
            val (qa, ka) = a.showQr(); val (qb, kb) = b.showQr()
            return Start.qr(a.keys, qa, ka, qb)!! to Start.qr(b.keys, qb, kb, qa)!!
        }
        val first = scan(); val second = scan()
        assertFalse(first.first.rk.contentEquals(second.first.rk))
        val seen = HashSet<String>()
        Aead.sealed = { k, n -> assertTrue("a key and nonce used again", seen.add(Vectors.hex(k + n))) }
        try {
            Ratchet.seal(first.first, byteArrayOf(1, 2, 'f'.code.toByte(), 'r'.code.toByte(), 'x'.code.toByte()))
            Ratchet.seal(second.first, byteArrayOf(1, 2, 'f'.code.toByte(), 'r'.code.toByte(), 'y'.code.toByte()))
        } finally { Aead.sealed = null }
        // An old packet does not open in the new session.
        val old = Ratchet.seal(first.first, byteArrayOf(1, 2, 'f'.code.toByte(), 'r'.code.toByte(), 'z'.code.toByte()))
        assertTrue(Ratchet.open(second.second, old.packet, b.keys) is Ratchet.Opened.NotMine)
    }

    @Test fun badQrStartsAreRefused() {
        val a = Phone("A"); val b = Phone("B")
        val (qa, ka) = a.showQr(); val (qb, _) = b.showQr()
        assertNull("own code", Start.qr(a.keys, qa, ka, qa))
        assertNull("one-time key not the one shown", Start.qr(a.keys, qa, KeyPairBytes.generate(), qb))
        assertNull("a code with an extra byte", QrCode.decode(qb.encode() + 0))
        assertNull("a code without its one-time key", QrCode.decode(qb.card.encode()))
    }

    @Test fun aQrStartStillWorksWithTheCardBeforeAPrekeyRotation() {
        val a = Phone("A"); val b = Phone("B")
        val (qa, ka) = a.showQr()
        a.keys = (a.keys as SoftKeys).rotated()
        val (qb, kb) = b.showQr()
        val sa = checkNotNull(Start.qr(a.keys, qa, ka, qb))
        val sb = checkNotNull(Start.qr(b.keys, qb, kb, qa))
        assertArrayEquals(sa.rk, sb.rk)
    }

    @Test fun aHelloOpensOnlyForTheLinksOwner() {
        val owner = Phone("Owner"); val opener = Phone("Opener"); val stranger = Phone("Stranger")
        val opened = checkNotNull(Start.link(opener.keys, org.phonemesh.core.account.Profile("Opener", 0x43A047, org.phonemesh.core.account.Avatar.Emoji("🐟")), owner.card, KeyPairBytes.generate()))
        assertTrue("one packet: ${opened.hello.size}", opened.hello.size == 110 + "Opener".length + 4)
        assertTrue(Start.isHello(opened.hello))
        assertNull("another phone", Start.hello(stranger.keys, opened.hello))
        assertNull("damaged", Start.hello(owner.keys, opened.hello.copyOf().also { it[60] = (it[60] + 1).toByte() }))
        val got = checkNotNull(Start.hello(owner.keys, opened.hello))
        assertEquals("Opener", got.name)
        assertEquals(0x43A047, got.colour)
        assertEquals(org.phonemesh.core.account.Avatar.Emoji("🐟"), got.profile.avatar)
        assertArrayEquals(opener.keys.identity, got.identity)
    }

    /** Review F8: the hello does not carry the opener's identity key in the clear. */
    /** The largest profile (40-byte name, 28-byte emoji) still makes a hello of one radio packet. */
    @Test fun theLargestHelloFitsOnePacket() {
        val owner = Phone("Owner"); val opener = Phone("Opener")
        val longest = org.phonemesh.core.account.EmojiAvatars.all.maxBy { it.toByteArray().size }
        val profile = org.phonemesh.core.account.Profile("é".repeat(20), 0xFFFFFF, org.phonemesh.core.account.Avatar.Emoji(longest))
        val hello = checkNotNull(Start.link(opener.keys, profile, owner.card, KeyPairBytes.generate())).hello
        assertEquals(110 + 40 + longest.toByteArray().size, hello.size)
        assertTrue(hello.size <= org.phonemesh.core.wire.WireV2.MAX_PACKET)
        assertEquals(profile, checkNotNull(Start.hello(owner.keys, hello)).profile)
    }

    @Test fun theHelloHidesWhoSendsIt() {
        val owner = Phone("Owner"); val opener = Phone("Opener")
        val hello = Start.link(opener.keys, org.phonemesh.core.account.Profile("Opener", 0x43A047, org.phonemesh.core.account.Avatar.Emoji("🐟")), owner.card, KeyPairBytes.generate())!!.hello
        val identity = opener.keys.identity
        assertFalse((0..hello.size - identity.size).any { at -> hello.copyOfRange(at, at + identity.size).contentEquals(identity) })
        assertFalse(Vectors.hex(hello).contains(AccountId.of(identity).toString()))
    }

    /** Review F10, F11: no date in the hello; a link from before a prekey rotation still works while the old key is kept. */
    @Test fun aHelloToAnOlderPrekeyStillOpensWhileItIsKept() {
        val owner = Phone("Owner"); val opener = Phone("Opener")
        val oldCard = owner.card
        owner.keys = (owner.keys as SoftKeys).rotated(keep = 1)
        val hello = Start.link(opener.keys, org.phonemesh.core.account.Profile("Opener", 0x43A047, org.phonemesh.core.account.Avatar.Emoji("🐟")), oldCard, KeyPairBytes.generate())!!.hello
        assertEquals("Opener", Start.hello(owner.keys, hello)!!.name)
        owner.keys = (owner.keys as SoftKeys).rotated(keep = 1)
        assertNull("two rotations later the old prekey is gone", Start.hello(owner.keys, hello))
    }

    /** Review F2: a replayed hello rebuilds the owner's state, but the owner's first packet steps with a fresh key. */
    @Test fun aReplayedHelloNeverMakesTheOwnerReuseAKey() {
        val owner = Phone("Owner"); val opener = Phone("Opener")
        val opened = Start.link(opener.keys, org.phonemesh.core.account.Profile("Opener", 0x43A047, org.phonemesh.core.account.Avatar.Emoji("🐟")), owner.card, KeyPairBytes.generate())!!
        val first = Start.hello(owner.keys, opened.hello)!!
        val replay = Start.hello(owner.keys, opened.hello)!!
        assertArrayEquals("same id: the app refuses it", first.id, replay.id)
        val seen = HashSet<String>()
        Aead.sealed = { k, n -> assertTrue("a key and nonce used again", seen.add(Vectors.hex(k + n))) }
        try {
            val one = Ratchet.seal(first.state, byteArrayOf(6) + ByteArray(10))
            val two = Ratchet.seal(replay.state, byteArrayOf(6) + ByteArray(10))
            assertTrue(one.stepped && two.stepped)
        } finally { Aead.sealed = null }
    }

    @Test fun aLinkConversationRunsBothWays() {
        val (opener, owner) = Phone.linkPair()
        assertEquals("ça va ?", owner.receive(opener.send("ça va ?").packet))
        val reply = owner.send("oui")
        assertTrue("the owner steps before its first packet", reply.stepped && reply.withKey)
        assertEquals("oui", opener.receive(reply.packet))
        val back = opener.send("super")
        assertTrue("then the opener", back.stepped)
        assertEquals("super", owner.receive(back.packet))
    }

    @Test fun theOwnersFirstLongPacketStillStepsAndWaitsForItsKey() {
        val (opener, owner) = Phone.linkPair()
        val long = owner.seal(org.phonemesh.core.wire.Body.Text("fr", "é".repeat(90)))
        assertTrue(long.stepped && !long.withKey)
        assertTrue(opener.open(long.packet) is Ratchet.Opened.Held)
        val receipt = owner.seal(org.phonemesh.core.wire.Body.Receipt(Ratchet.ack(owner.state)!!))
        assertTrue(receipt.withKey)
        assertTrue(opener.open(receipt.packet) is Ratchet.Opened.Delivered)
        assertEquals("é".repeat(90), opener.receive(long.packet))
    }
}
