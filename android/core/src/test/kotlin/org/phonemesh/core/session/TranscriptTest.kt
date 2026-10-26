package org.phonemesh.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.phonemesh.core.account.ContactCard
import org.phonemesh.core.account.QrCode
import org.phonemesh.core.crypto.Vectors
import org.phonemesh.core.wire.Body

/**
 * Replays the transcript an independent Python implementation of the same rules wrote
 * (.local/crypto-vectors/transcript.py): every packet must come out byte for byte, every receipt the same.
 */
class TranscriptTest {
    private class Side(val keys: SoftKeys, val oneTime: KeyPairBytes, val pairs: ArrayDeque<KeyPairBytes>) {
        var state: SessionState? = null
        val card get() = ContactCard.create(keys, org.phonemesh.core.account.Profile("x", 0))
    }

    @Test fun kotlinAndPythonAgreeByteForByte() {
        val sides = HashMap<String, Side>()
        val packets = HashMap<Int, ByteArray>()
        var checked = 0
        fun pair(scalar: String, public: String) = KeyPairBytes(Vectors.hex(scalar), Vectors.hex(public))
        for (line in Vectors.lines("transcript.txt")) {
            when (line[0]) {
                "phone" -> sides[line[1]] = Side(SoftKeys(pair(line[2], line[3]), pair(line[4], line[5])), pair(line[6], line[7]), ArrayDeque())
                "ratchet" -> sides.getValue(line[1]).pairs.addLast(pair(line[2], line[3]))
                "qr" -> {
                    val (a, b) = sides.getValue("A") to sides.getValue("B")
                    val qa = QrCode(a.card, a.oneTime.public); val qb = QrCode(b.card, b.oneTime.public)
                    a.state = checkNotNull(Start.qr(a.keys, qa, a.oneTime, qb))
                    b.state = checkNotNull(Start.qr(b.keys, qb, b.oneTime, qa))
                    assertEquals(line[1], "L=" + if (a.state!!.iAmL) "A" else "B")
                }
                "send" -> {
                    val side = sides.getValue(line[1])
                    val text = if (line[3] == "long" || line[3] == "longfirst") "e".repeat(170) else line[3]
                    val sealed = Ratchet.seal(side.state!!, Body.encode(Body.Text("fr", text))) { side.pairs.removeFirst() }
                    side.state = sealed.state
                    assertEquals("packet ${line[2]}", line[4], Vectors.hex(sealed.packet))
                    packets[line[2].toInt()] = sealed.packet
                    checked++
                }
                "recv" -> {
                    val side = sides.getValue(line[1])
                    val got = when (val opened = Ratchet.open(side.state!!, packets.getValue(line[2].toInt()), side.keys)) {
                        is Ratchet.Opened.Delivered -> { side.state = opened.state; (Body.decode(opened.body) as Body.Text).text }
                        Ratchet.Opened.Held -> "held"
                        Ratchet.Opened.NotMine -> "-"
                    }
                    assertEquals("receipt of ${line[2]} by ${line[1]}", line[3], got)
                    checked++
                }
                "link" -> {
                    val owner = sides.getValue(line[1]); val opener = sides.getValue(line[2])
                    val opened = checkNotNull(Start.link(opener.keys, org.phonemesh.core.account.Profile("Pélagie", 0x43A047), owner.card, opener.oneTime))
                    opener.state = opened.state
                    packets[-1] = opened.hello
                    val received = checkNotNull(Start.hello(owner.keys, opened.hello))
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
        assertEquals("every send, receipt and the hello was checked", 31, checked)
    }
}
