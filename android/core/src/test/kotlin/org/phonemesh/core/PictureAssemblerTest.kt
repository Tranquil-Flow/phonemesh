package org.phonemesh.core

import org.junit.Assert.*
import org.junit.Test

class PictureAssemblerTest {
    private var clock = 1_000L
    private val assembler = PictureAssembler(now = { clock })
    private val sketch = ByteArray(21) { it.toByte() }
    private fun picture(n: Int) = ByteArray(n) { (it * 7).toByte() }
    private fun packets(id: Int, n: Int) = PictureWire.split(id, PictureCodec.WEBP, 96, 64, picture(n), sketch)

    @Test fun inOrderPictureCompletes() {
        val p = packets(5, 431)
        val events = p.flatMap { assembler.accept("!a", it) }
        assertTrue(events.first() is PictureAssembler.Event.Started)
        val done = events.last() as PictureAssembler.Event.Done
        assertArrayEquals(picture(431), done.bytes)
        assertEquals(96, done.head.width)
        assertEquals(listOf(1, 2), events.filterIsInstance<PictureAssembler.Event.Progress>().map { it.have })
        assertEquals(0, assembler.pending)
    }

    @Test fun partsBeforeHeadAreKept() {
        val p = packets(6, 431)
        assertEquals(emptyList<Any>(), assembler.accept("!a", p[2]) + assembler.accept("!a", p[3]))
        val events = assembler.accept("!a", p[0]) + assembler.accept("!a", p[1])
        assertArrayEquals(picture(431), (events.last() as PictureAssembler.Event.Done).bytes)
    }

    @Test fun duplicatesAndLateResendsChangeNothing() {
        val p = packets(7, 300)
        assembler.accept("!a", p[0]); assembler.accept("!a", p[1])
        assertEquals(emptyList<Any>(), assembler.accept("!a", p[1]))
        assertTrue(assembler.accept("!a", p[2]).last() is PictureAssembler.Event.Done)
        // The other radio did not hear our ACK and sent the last part again: no second picture.
        assertEquals(emptyList<Any>(), assembler.accept("!a", p[2]))
        assertEquals(emptyList<Any>(), assembler.accept("!a", p[0]))
        assertEquals(0, assembler.pending)
    }

    @Test fun sameIdFromAnotherSenderIsAnotherPicture() {
        val p = packets(8, 150)
        assertTrue(assembler.accept("!a", p[0]).first() is PictureAssembler.Event.Started)
        assertTrue(assembler.accept("!b", p[0]).first() is PictureAssembler.Event.Started)
        assertEquals(2, assembler.pending)
    }

    @Test fun wrongChecksumIsBroken() {
        val p = packets(9, 300).toMutableList()
        val part = p[2] as PictureWire.Part
        p[2] = part.copy(data = part.data.copyOf().also { it[0] = (it[0] + 1).toByte() })
        val events = p.flatMap { assembler.accept("!a", it) }
        assertEquals(PictureAssembler.Event.Broken("!a", 9), events.last())
        assertEquals(0, assembler.pending)
    }

    @Test fun wrongPartSizeIsBroken() {
        val p = packets(10, 300).toMutableList()
        val part = p[1] as PictureWire.Part
        p[1] = part.copy(data = part.data.copyOf(150))
        assertEquals(PictureAssembler.Event.Broken("!a", 10), p.flatMap { assembler.accept("!a", it) }.last())
    }

    @Test fun partBeyondTheHeadCountIsIgnored() {
        val p = packets(11, 150)
        assembler.accept("!a", p[0])
        assertEquals(emptyList<Any>(), assembler.accept("!a", PictureWire.Part(11, 5, ByteArray(10))))
    }

    @Test fun sketchOnlyIsDoneAtOnce() {
        val head = PictureWire.split(12, PictureCodec.SKETCH, 4, 3, ByteArray(0), sketch).single()
        val events = assembler.accept("!a", head)
        assertEquals(2, events.size)
        assertEquals(0, (events.last() as PictureAssembler.Event.Done).bytes.size)
    }

    @Test fun quietPicturesExpire() {
        val p = packets(13, 431)
        assembler.accept("!a", p[0]); assembler.accept("!a", p[1])
        clock += PictureAssembler.QUIET_MS - 1
        assertEquals(emptyList<Any>(), assembler.expire())
        assembler.accept("!a", p[2]) // progress restarts the clock
        clock += PictureAssembler.QUIET_MS - 1
        assertEquals(emptyList<Any>(), assembler.expire())
        clock += 2
        assertEquals(listOf(PictureAssembler.Event.Expired("!a", 13)), assembler.expire())
        assertEquals(0, assembler.pending)
    }

    @Test fun headlessPartsExpireSilently() {
        assembler.accept("!a", packets(14, 431)[1])
        clock += PictureAssembler.QUIET_MS + 1
        assertEquals(emptyList<Any>(), assembler.expire())
        assertEquals(0, assembler.pending)
    }

    @Test fun boundsDropTheOldest() {
        for (id in 1..PictureAssembler.MAX_PER_SENDER + 1) { clock++; assembler.accept("!a", packets(100 + id, 431)[0]) }
        assertEquals(PictureAssembler.MAX_PER_SENDER, assembler.pending)
        for (s in 0 until 10) { clock++; assembler.accept("!s$s", packets(200 + s, 431)[0]) }
        assertEquals(PictureAssembler.MAX_TOTAL, assembler.pending)
    }
}
