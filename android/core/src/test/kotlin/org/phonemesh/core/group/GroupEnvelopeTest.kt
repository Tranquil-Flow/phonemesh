package org.phonemesh.core.group

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.Aead
import org.phonemesh.core.crypto.P256
import java.security.KeyPair

/**
 * Op 8, the relayed group envelope (v0.1.3 G3a; relayed group control design, "The envelope"; decisions 79 and 80).
 * Vectors from `v013/g3a-envelope-vectors.py` on pyca/cryptography, written from the design note alone (same author:
 * a cross-implementation check, not an independent review).
 */
class GroupEnvelopeTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/group/envelope-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val group get() = hex("group")
    private val epoch get() = v.getValue("epoch").toLong()
    private val admin get() = hex("admin")
    private val recipient get() = hex("recipient")
    private val other get() = hex("otherAdmin")
    private val adminSigner = P256.compress(P256.generate().public as java.security.interfaces.ECPublicKey)
    private fun box(scalar: String, public: String) = GroupEnvelope.BoxKey(P256.privateKey(hex(scalar)), hex(public))
    private val current get() = box("boxScalar", "box")
    private val previous get() = box("boxPrevScalar", "boxPrev")

    /** The key the vector carries, as the admin holds it: its own signing key goes in the key, not in the envelope. */
    private fun vectorKey() = GroupKey(group, epoch, hex("secret"), hex("digest"), hex("parent"), v.getValue("parts").toInt(), adminSigner)

    private fun open(body: ByteArray, boxes: List<GroupEnvelope.BoxKey> = listOf(current), group: ByteArray = this.group,
                     epoch: Long = this.epoch, admin: ByteArray = this.admin, recipient: ByteArray = this.recipient) =
        GroupEnvelope.open(body, group, epoch, admin, adminSigner, recipient, boxes)

    @After fun unhook() { Aead.sealed = null }

    @Test fun kotlinSealsExactlyTheSecondImplementationsBytes() {
        val eph = KeyPair(P256.publicKey(hex("eph")), P256.privateKey(hex("ephScalar")))
        val body = GroupEnvelope.seal(vectorKey(), admin, recipient, hex("box"), eph)
        assertEquals(GroupEnvelope.SIZE, body.size)
        assertEquals(120, GroupEnvelope.SIZE)
        assertTrue("an envelope fits one group packet", GroupEnvelope.SIZE <= GroupCipher.MAX_BODY)
        assertArrayEquals(hex("body"), body)
    }

    @Test fun theRecipientOpensTheSecondImplementationsEnvelopeIntoTheKeyItReplaces() {
        val key = open(hex("body"))
        assertNotNull(key)
        key!!
        val want = vectorKey()
        assertArrayEquals(want.encode(), key.encode())
        assertArrayEquals("the admin's signing key comes from the list held, not the envelope", adminSigner, key.signer)
        assertEquals(7L, key.epoch)
        assertEquals(3, key.parts)
    }

    @Test fun thePreviousBoxKeyStillOpensWhileKeptAndOnlyThen() {
        assertNotNull("tried after the current key", open(hex("bodyPrev"), listOf(current, previous)))
        assertNotNull(open(hex("bodyPrev"), listOf(previous)))
        assertNull("previous half wiped: not for me", open(hex("bodyPrev"), listOf(current)))
        assertNull("sealed to the current key", open(hex("body"), listOf(previous)))
        assertNull("no box key", open(hex("body"), emptyList()))
    }

    /**
     * Each refusal is checked on a body this code just sealed and on the second implementation's, each first shown to
     * open for the right recipient, admin, group and epoch (a positive control in the same test): so a refusal cannot
     * pass only because nothing opens at all, e.g. when a binding is dropped on both sides (round 1 of the G3a mutants).
     * Lazy, so each body's control runs just before its own refusals and a refusal is reached even when a later body
     * would fail its control (round 2 caught M1-M3 only by the pyca body's control).
     */
    private fun bodies(): Sequence<ByteArray> = sequenceOf(GroupEnvelope.seal(vectorKey(), admin, recipient, hex("box")), hex("body"))
        .onEach { assertArrayEquals("positive control", vectorKey().encode(), open(it)!!.encode()) }

    @Test fun anotherMemberHoldingTheSameBoxKeyCannotOpenIt() {
        // The recipient's identity is bound in the HKDF info: a copied or duplicated box key does not open for another.
        for (body in bodies()) {
            assertNull(open(body, recipient = other))
            assertNull(open(body, recipient = admin))
        }
    }

    @Test fun anEnvelopeCopiedOntoAnotherAdminsChainIsRefused() {
        for (body in bodies()) {
            assertNull(open(body, admin = other))
            assertNull(open(body, admin = recipient))
        }
    }

    @Test fun anotherGroupOrEpochIsRefused() {
        val g = group.also { it[0] = (it[0] + 1).toByte() }
        for (body in bodies()) {
            assertNull(open(body, group = g))
            assertNull("the epoch held, not the next", open(body, epoch = epoch - 1))
            assertNull(open(body, epoch = epoch + 1))
        }
    }

    @Test fun aRemovedMemberWithOnlyItsOwnBoxKeyCannotOpenIt() {
        val removed = P256.generate()
        val removedBox = GroupEnvelope.BoxKey(removed.private, P256.compress(removed.public as java.security.interfaces.ECPublicKey))
        for (body in bodies()) {
            assertNull(open(body, listOf(removedBox)))
            assertNull(open(body, listOf(removedBox), recipient = other))
        }
    }

    @Test fun everyChangedByteIsRefused() {
        for (body in bodies()) for (i in body.indices) for (bit in intArrayOf(0x01, 0x80)) {
            val bad = body.copyOf().also { it[i] = (it[i].toInt() xor bit).toByte() }
            assertNull("byte $i bit $bit", open(bad))
        }
    }

    @Test fun wrongSizesAreRefusedWithoutThrowing() {
        assertNull(open(ByteArray(0)))
        assertNull(open(byteArrayOf(GroupEnvelope.OP.toByte())))
        for (body in bodies()) {
            assertNull(open(body.copyOf(body.size - 1)))
            assertNull(open(body + byteArrayOf(0)))
        }
    }

    @Test fun aMalformedAdminsEnvelopeIsRefusedNotThrown() {
        bodies().toList()
        assertNull("inner epoch differs from the bound one", open(hex("bodyInnerEpoch")))
        assertNull("zero list parts", open(hex("bodyZeroParts")))
    }

    @Test fun everySealUsesItsOwnEphemeralKeyAndKeyNoncePair() {
        val pairs = mutableListOf<Pair<List<Byte>, List<Byte>>>()
        Aead.sealed = { k, n -> pairs += k.toList() to n.toList() }
        val a = GroupEnvelope.seal(vectorKey(), admin, recipient, hex("box"))
        val b = GroupEnvelope.seal(vectorKey(), admin, recipient, hex("box"))
        assertEquals(2, pairs.size)
        assertEquals("no key or nonce used twice", 2, pairs.toSet().size)
        assertFalse(pairs[0].first == pairs[1].first || pairs[0].second == pairs[1].second)
        assertFalse("fresh ephemeral key each time", a.copyOfRange(2, 35).contentEquals(b.copyOfRange(2, 35)))
        assertArrayEquals(vectorKey().encode(), open(a)!!.encode())
        assertArrayEquals(vectorKey().encode(), open(b)!!.encode())
    }

    @Test fun sealingRefusesWhatIsNotAKey() {
        val bad = hex("box").also { it[0] = 4 }
        assertThrows(IllegalArgumentException::class.java) { GroupEnvelope.seal(vectorKey(), admin, recipient, bad) }
        assertThrows(IllegalArgumentException::class.java) { GroupEnvelope.seal(vectorKey(), admin.copyOf(32), recipient, hex("box")) }
        assertThrows(IllegalArgumentException::class.java) { GroupEnvelope.seal(vectorKey(), admin, recipient.copyOf(34), hex("box")) }
    }
}
