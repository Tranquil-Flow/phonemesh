package org.phonemesh.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.crypto.Vectors
import org.phonemesh.core.wire.Body

/**
 * Replays the transcript an independent Python implementation of the same rules wrote
 * (.local/crypto-vectors/transcript.py): every packet must come out byte for byte, every receipt the same.
 */
class TranscriptTest {
    private class Side(val keys: SoftKeys, val pairs: ArrayDeque<KeyPairBytes>) {
        lateinit var state: SessionState
        val card get() = ContactCard.create(keys, "x", 0)
    }

    @Test fun kotlinAndPythonAgreeByteForByte() {
        val sides = HashMap<String, Side>()
        val packets = HashMap<Int, ByteArray>()
        var checked = 0
        for (line in Vectors.lines("transcript.txt")) {
            when (line[0]) {
                "phone" -> sides[line[1]] = Side(
                    SoftKeys(KeyPairBytes(Vectors.hex(line[2]), Vectors.hex(line[3])), KeyPairBytes(Vectors.hex(line[4]), Vectors.hex(line[5]))),
                    ArrayDeque(),
                )
                "ratchet" -> sides.getValue(line[1]).pairs.addLast(KeyPairBytes(Vectors.hex(line[2]), Vectors.hex(line[3])))
                "qr" -> {
                    val (a, b) = sides.getValue("A") to sides.getValue("B")
                    a.state = checkNotNull(Start.qr(a.keys, b.card))
                    b.state = checkNotNull(Start.qr(b.keys, a.card))
                    assertEquals(line[1], "L=" + if (a.state.iAmL) "A" else "B")
                }
                "send" -> {
                    val side = sides.getValue(line[1])
                    val text = if (line[3] == "long") "e".repeat(170) else line[3]
                    val sealed = Ratchet.seal(side.state, Body.encode(Body.Text("fr", text))) { side.pairs.removeFirst() }
                    assertEquals("packet ${line[2]}", line[4], Vectors.hex(sealed.packet))
                    packets[line[2].toInt()] = sealed.packet
                    checked++
                }
                "recv" -> {
                    val side = sides.getValue(line[1])
                    val got = when (val opened = Ratchet.open(side.state, packets.getValue(line[2].toInt()), side.keys)) {
                        is Ratchet.Opened.Delivered -> (Body.decode(opened.body) as Body.Text).text
                        Ratchet.Opened.Held -> "held"
                        Ratchet.Opened.NotMine -> "-"
                    }
                    assertEquals("receipt of ${line[2]} by ${line[1]}", line[3], got)
                    checked++
                }
                "link" -> {
                    val owner = sides.getValue(line[1]); val opener = sides.getValue(line[2])
                    val opened = checkNotNull(Start.link(opener.keys, "Pélagie", 4, owner.card,
                        KeyPairBytes(Vectors.hex(line[3]), Vectors.hex(line[4])), line[5].toLong()))
                    opener.state = opened.state
                    packets[-1] = opened.hello
                    val received = checkNotNull(Start.hello(owner.keys, opened.hello, line[5].toLong()))
                    assertEquals("Pélagie", received.name)
                    owner.state = received.state
                }
                "hello" -> {
                    assertEquals("hello", line[2], Vectors.hex(packets.getValue(-1)))
                    packets[line[1].toInt()] = packets.getValue(-1)
                    checked++
                }
            }
        }
        assertTrue("checked $checked", checked >= 30)
    }
}
