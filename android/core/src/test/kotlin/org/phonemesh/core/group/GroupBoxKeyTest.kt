package org.phonemesh.core.group

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.crypto.P256
import java.security.interfaces.ECPublicKey

/**
 * Op 9, the announced group box key (v0.1.3 G3b; relayed group control design, "New group ops" and "Box keys";
 * accounts decisions 79 and 80). Vectors from `v013/g3b-boxkey-vectors.py`, written from the design note alone
 * (same author: a cross-implementation check, not an independent review).
 *
 * The body has no cryptography of its own: the announcing member is the radio chain the body travels on, signed with
 * that member's group signing key. What is checked here is the body layout, the refusals and the ownership rules of
 * the keys a member keeps: one key per member of the list held, the newest that member announced on its own chain.
 */
class GroupBoxKeyTest {
    private val v: Map<String, String> = javaClass.getResourceAsStream("/group/boxkey-vectors.txt")!!.bufferedReader()
        .readLines().filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun hex(k: String) = v.getValue(k).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val public get() = hex("public")
    private val body get() = hex("body")

    /** A fresh identity key: a member of the list held. */
    private fun id() = P256.compress(P256.generate().public as ECPublicKey)

    /** A fresh public key: what a member announces as its box key. */
    private fun box() = P256.compress(P256.generate().public as ECPublicKey)

    // ---- the body

    @Test fun theBodyIsTheSecondImplementationsBytes() {
        val b = GroupBoxKey.encode(public)
        assertEquals(35, b.size)
        assertEquals(GroupBoxKey.SIZE, b.size)
        assertEquals(9, b[0].toInt())
        assertEquals(1, b[1].toInt())
        assertArrayEquals(public, b.copyOfRange(2, 35))
        assertArrayEquals("the second implementation's bytes", body, b)
        assertTrue("an announcement fits one group packet", GroupBoxKey.SIZE <= GroupCipher.MAX_BODY)
    }

    @Test fun theAnnouncedKeyComesBackOut() {
        assertArrayEquals(public, GroupBoxKey.decode(body))
        assertArrayEquals(public, GroupBoxKey.decode(GroupBoxKey.encode(public)))
    }

    /**
     * Each refusal is checked on a body this code just encoded and on the second implementation's, each first shown to
     * decode for the right key (a positive control in the same test): so a refusal cannot pass only because nothing
     * decodes at all.
     */
    private fun bodies(): Sequence<ByteArray> = sequenceOf(GroupBoxKey.encode(public), body)
        .onEach { assertArrayEquals("positive control", public, GroupBoxKey.decode(it)) }

    @Test fun anotherVersionOpOrSizeIsRefused() {
        for (b in bodies()) {
            assertNull("another version", GroupBoxKey.decode(b.copyOf().also { it[1] = 2 }))
            assertNull("another op", GroupBoxKey.decode(b.copyOf().also { it[0] = 10 }))
            assertNull("too short", GroupBoxKey.decode(b.copyOfRange(0, b.size - 1)))
            assertNull("too long", GroupBoxKey.decode(b + byteArrayOf(0)))
            assertNull("nothing at all", GroupBoxKey.decode(ByteArray(0)))
        }
    }

    @Test fun aKeyThatIsNotOnTheCurveIsRefused() {
        for (b in bodies()) {
            assertNull("not a point", GroupBoxKey.decode(b.copyOf().also { it[2] = 0 }))
            assertNull("not a point", GroupBoxKey.decode(hex("bodyNotAPoint")))
        }
    }

    @Test fun aChangedOpOrVersionByteIsRefused() {
        for (b in bodies()) for (i in 0..1) for (bit in intArrayOf(0x01, 0x80)) {
            val bad = b.copyOf().also { it[i] = (it[i].toInt() xor bit).toByte() }
            assertNull("byte $i bit $bit", GroupBoxKey.decode(bad))
        }
    }

    @Test fun aChangedKeyByteNeverYieldsTheAnnouncedKey() {
        for (b in bodies()) for (i in 2 until b.size) for (bit in intArrayOf(0x01, 0x80)) {
            val bad = b.copyOf().also { it[i] = (it[i].toInt() xor bit).toByte() }
            val got = GroupBoxKey.decode(bad)
            assertFalse("byte $i bit $bit", got != null && got.contentEquals(public))
        }
    }

    @Test fun encodingRefusesWhatIsNotAKey() {
        assertThrows(IllegalArgumentException::class.java) { GroupBoxKey.encode(public.copyOf(32)) }
        assertThrows(IllegalArgumentException::class.java) { GroupBoxKey.encode(public.copyOf(34)) }
        assertThrows(IllegalArgumentException::class.java) { GroupBoxKey.encode(public.copyOf().also { it[0] = 0 }) }
    }

    @Test fun theBodyNamesNoOwner() {
        // The announcing member is the chain the body travels on: there is no room in the body for an owner to name.
        assertEquals(35, body.size)
        assertNull("no room for an owner", GroupBoxKey.decode(body + hex("public")))
    }

    // ---- the keys a member keeps

    private val ama = id()
    private val kofi = id()
    private val esi = id()

    /** The keys of a member of a list held by [members]. */
    private fun keys(vararg members: ByteArray) = GroupBoxKeys { m -> members.any { it.contentEquals(m) } }

    @Test fun aMemberKeepsTheKeyItAnnounced() {
        val t = keys(ama, kofi)
        assertTrue(t.learn(ama, body, 7, 0))
        assertArrayEquals(public, t.keyOf(ama))
        assertNull("nobody announced anything else", t.keyOf(kofi))
        assertEquals(7L, t.epochOf(ama))
    }

    @Test fun theNewestKeyWins() {
        val t = keys(ama)
        val newer = box()
        assertTrue(t.learn(ama, body, 7, 0))
        assertTrue(t.learn(ama, GroupBoxKey.encode(newer), 7, 9))
        assertArrayEquals(newer, t.keyOf(ama))
        assertEquals(7L, t.epochOf(ama))
    }

    @Test fun aLaterPositionOnTheChainIsNewerThanAnEarlierOne() {
        // The reader may open a later chain position before an earlier one (out of order inside the reading window),
        // so "newest" is the chain position, not the order the packets were read in.
        val t = keys(ama)
        val newer = box()
        assertTrue(t.learn(ama, body, 7, 5))
        assertTrue(t.learn(ama, GroupBoxKey.encode(newer), 7, 9))
        assertFalse("an earlier position does not replace it", t.learn(ama, body, 7, 3))
        assertArrayEquals(newer, t.keyOf(ama))
    }

    @Test fun aNewerEpochIsNewerThanAnyPositionOfTheOlderOne() {
        val t = keys(ama)
        val newer = box()
        assertTrue(t.learn(ama, body, 7, 900))
        assertTrue(t.learn(ama, GroupBoxKey.encode(newer), 8, 0))
        assertArrayEquals(newer, t.keyOf(ama))
        assertEquals(8L, t.epochOf(ama))
        assertFalse("the older epoch does not come back", t.learn(ama, body, 7, 901))
        assertArrayEquals(newer, t.keyOf(ama))
    }

    @Test fun aKeyAnotherMemberAlreadyHoldsIsRefused() {
        val t = keys(ama, kofi)
        assertTrue(t.learn(ama, body, 7, 0))
        assertFalse("one key for two members", t.learn(kofi, body, 7, 0))
        assertNull("kofi announced nothing", t.keyOf(kofi))
        assertArrayEquals(public, t.keyOf(ama))
    }

    @Test fun aKeyFromSomeoneOutsideTheListHeldIsRefused() {
        val t = keys(ama)
        assertTrue(t.learn(ama, body, 7, 0))
        // A key nobody holds yet, so nothing else can refuse it but the list held.
        assertFalse(t.learn(kofi, GroupBoxKey.encode(box()), 7, 0))
        assertNull(t.keyOf(kofi))
        assertArrayEquals("ama keeps what it announced", public, t.keyOf(ama))
    }

    @Test fun aMalformedAnnouncementIsRefused() {
        val t = keys(ama)
        assertFalse(t.learn(ama, hex("bodyWrongVersion"), 7, 0))
        assertFalse(t.learn(ama, hex("bodyWrongOp"), 7, 0))
        assertFalse(t.learn(ama, hex("bodyShort"), 7, 0))
        assertFalse(t.learn(ama, hex("bodyLong"), 7, 0))
        assertFalse(t.learn(ama, hex("bodyNotAPoint"), 7, 0))
        assertNull(t.keyOf(ama))
    }

    @Test fun aKeyIsDroppedWhenItsMemberLeavesTheList() {
        val t = keys(ama, kofi)
        assertTrue(t.learn(ama, body, 7, 0))
        assertTrue(t.learn(kofi, GroupBoxKey.encode(box()), 7, 0))
        t.forget(kofi)
        assertNull("kofi left", t.keyOf(kofi))
        assertArrayEquals("ama stays", public, t.keyOf(ama))
        assertEquals(1, t.owners.size)
    }

    @Test fun nothingIsKeptWhenTheGroupIsGone() {
        val t = keys(ama, kofi)
        assertTrue(t.learn(ama, body, 7, 0))
        t.clear()
        assertEquals(0, t.owners.size)
        assertNull(t.keyOf(ama))
        // Nothing is kept, so the same key is free for anyone to announce.
        assertTrue(t.learn(kofi, body, 7, 0))
        assertArrayEquals(public, t.keyOf(kofi))
    }

    @Test fun aMemberNeverHoldsAnotherMembersKey() {
        val t = keys(ama, kofi)
        assertTrue(t.learn(ama, body, 7, 0))
        assertNull(t.keyOf(kofi))
        assertEquals(listOf(ama.toList()), t.owners.map { it.toList() })
    }

    @Test fun aRotatedKeyIsNotClaimedByAnotherMember() {
        val t = keys(ama, kofi, esi)
        assertTrue(t.learn(ama, body, 7, 0))
        assertTrue("ama rotates", t.learn(ama, GroupBoxKey.encode(box()), 7, 9))
        assertFalse("ama's old key is not kofi's to claim", t.learn(kofi, body, 7, 9))
        assertNull(t.keyOf(kofi))
        assertNull("nor esi's", t.keyOf(esi))
    }

    @Test fun aKeyAMemberLeftIsNotClaimedByAnotherMember() {
        val t = keys(ama, kofi, esi)
        assertTrue(t.learn(kofi, body, 7, 0))
        t.forget(kofi)
        assertNull("kofi left", t.keyOf(kofi))
        assertFalse("kofi's key is not esi's to claim", t.learn(esi, body, 7, 9))
        assertNull(t.keyOf(esi))
    }

    @Test fun aMemberMayAnnounceItsOwnKeyAgain() {
        val t = keys(ama)
        assertTrue(t.learn(ama, body, 7, 0))
        assertTrue("ama may re-announce its own key", t.learn(ama, body, 7, 9))
        assertArrayEquals(public, t.keyOf(ama))
    }

    @Test fun theSameAnnouncementReadTwiceIsNotTakenTwice() {
        // The chain reader opens a position once, so the same announcement at the same position is a replay.
        val t = keys(ama)
        assertTrue(t.learn(ama, body, 7, 3))
        assertFalse("the same position again", t.learn(ama, body, 7, 3))
        assertArrayEquals(public, t.keyOf(ama))
        assertEquals(1, t.owners.size)
    }

    @Test fun theKeyHandedOutIsACopy() {
        val t = keys(ama)
        assertTrue(t.learn(ama, body, 7, 0))
        val taken = t.keyOf(ama)!!
        taken[0] = (taken[0] + 1).toByte()
        assertArrayEquals("the kept key is not changed from outside", public, t.keyOf(ama))
    }

    // ---- the saved keys (v0.1.3 G3d)

    @Test fun theKeysSurviveASaveAndLoad() {
        val t = keys(ama, kofi)
        val kofis = box()
        assertTrue(t.learn(ama, body, 7, 0))
        assertTrue(t.learn(kofi, GroupBoxKey.encode(kofis), 8, 3))
        val loaded = GroupBoxKeys.decode(t.encode())
        assertNotNull("the saved keys load", loaded)
        assertArrayEquals("ama's key", public, loaded!!.keyOf(ama))
        assertArrayEquals("kofi's key", kofis, loaded.keyOf(kofi))
        assertEquals(7L, loaded.epochOf(ama))
        assertEquals(8L, loaded.epochOf(kofi))
    }

    @Test fun aKeyOfAMemberNotOnTheListHeldIsNotLoaded() {
        val t = keys(ama, kofi)
        assertTrue(t.learn(ama, body, 7,0))
        assertTrue(t.learn(kofi, GroupBoxKey.encode(box()), 7, 0))
        // The list held now has no Kofi: its key is not kept, but the claim stays so nobody may take it.
        val loaded = GroupBoxKeys.decode(t.encode())
        assertNotNull(loaded)
        val here = keys(ama)
        here.restore(loaded!!)
        assertArrayEquals("ama's key", public, here.keyOf(ama))
        assertNull("kofi is not on the list held", here.keyOf(kofi))
        assertFalse("and nobody may take kofi's key", here.learn(esi, GroupBoxKey.encode(box()), 7, 0))
    }

    @Test fun aRotatedKeyStaysClaimedAcrossASaveAndLoad() {
        val t = keys(ama, kofi)
        val rotated = box()
        assertTrue(t.learn(ama, body, 7, 0))
        assertTrue("ama rotates", t.learn(ama, GroupBoxKey.encode(rotated), 7, 9))
        val loaded = GroupBoxKeys.decode(t.encode())
        assertNotNull(loaded)
        val here = keys(ama, kofi)
        here.restore(loaded!!)
        assertArrayEquals("the newest key", rotated, here.keyOf(ama))
        assertFalse("ama's old key is not kofi's to claim", here.learn(kofi, body, 7, 9))
        assertNull(here.keyOf(kofi))
    }

    @Test fun whatIsNotASavedSetOfKeysIsRefused() {
        val t = keys(ama)
        assertTrue(t.learn(ama, body, 7, 0))
        val bytes = t.encode()
        assertNull("another version", GroupBoxKeys.decode(bytes.copyOf().also { it[0] = 2 }))
        assertNull("cut short", GroupBoxKeys.decode(bytes.copyOf(bytes.size - 1)))
        assertNull("trailing bytes", GroupBoxKeys.decode(bytes + byteArrayOf(0)))
        assertNull("nothing at all", GroupBoxKeys.decode(ByteArray(0)))
    }
}
