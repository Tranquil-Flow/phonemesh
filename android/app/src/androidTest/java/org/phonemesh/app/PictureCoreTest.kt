package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.DeliveryState
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire

/** Pictures through AppCore with the fake radio: one packet at a time, retries, arrival, loss, private chats only. */
@RunWith(AndroidJUnit4::class)
class PictureCoreTest {
    private val core get() = TestCore.core
    private val radio get() = TestCore.radio
    private fun <T> main(block: () -> T): T = TestCore.onMain(block)

    @After fun tearDown() = TestCore.uninstall()

    private fun photo(): Bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 300) for (y in 0 until 200) setPixel(x, y, Color.rgb((x * 7) % 256, (y * 5) % 256, (x + y) % 256))
    }

    @Test fun aPictureGoesOutOnePacketAtATimeAndIsDeliveredWhenAllAreReceived() {
        TestCore.install()
        val (bea, chat) = PersonFixture.meet("Kofi", accept = false)
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(picture.bytes.size <= PictureSize.MEDIUM.budget)
        assertTrue(main { core.sendPicture(chat, picture) })
        val message = checkNotNull(core.history.lastMessage(chat))
        assertEquals(MessageKind.PICTURE, message.kind)
        assertEquals(DeliveryStatus.WAITING, message.status)
        val total = PictureWire.partsForV2(picture.bytes.size) + 1
        for (i in 0 until total) {
            assertEquals("only one packet in flight", i + 1, radio.broadcasts.size)
            val packet = radio.broadcasts.last()
            assertEquals(MeshRadio.ACCOUNT_PORT, packet.port)
            main { radio.deliver(packet.packetId, DeliveryState.SUBMITTING) }
            assertEquals("being written is not enough", i + 1, radio.broadcasts.size)
            main { radio.deliver(packet.packetId, DeliveryState.QUEUED) }
            if (i < total - 1) Views.waitUntil { radio.broadcasts.size == i + 2 }
        }
        assertEquals(total, radio.broadcasts.size)
        assertEquals("Sent once every packet is taken", DeliveryStatus.SENT, core.history.message(message.id)!!.status)
        radio.broadcasts.forEach { bea.postman.receive(it.payload, 1_000) }
        PersonFixture.deliver(bea.postman.due(60_000).first().packet)
        assertEquals(DeliveryStatus.DELIVERED, core.history.message(message.id)!!.status)
        assertEquals(total, core.history.picture(message.id)!!.have)
        assertTrue(radio.sent.isEmpty() && radio.sentData.isEmpty())
    }

    @Test fun aPictureWithoutARadioWaitsKeptAndGoesWhenOneIsReady() {
        TestCore.install(connected = false)
        val (_, chat) = PersonFixture.meet("Kofi", accept = false)
        assertTrue(main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) })
        val message = checkNotNull(core.history.lastMessage(chat))
        assertEquals("waiting for a radio, every packet kept on this phone", DeliveryStatus.WAITING, message.status)
        assertTrue(radio.broadcasts.isEmpty())
        main { radio.connected(true) }
        Views.waitUntil { radio.broadcasts.size == 1 }
        assertEquals("the first packet goes once a radio is ready", 1, radio.broadcasts.size)
    }

    @Test fun aRejectedPictureWriteIsOfferedAgainUnchangedAndAFailedPictureCanBeSentAgain() {
        TestCore.install()
        val (bea, chat) = PersonFixture.meet("Kofi", accept = false)
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) }
        val message = checkNotNull(core.history.lastMessage(chat))
        val first = radio.broadcasts.single()
        main { radio.deliver(first.packetId, DeliveryState.UNCONFIRMED) }
        assertEquals("not lost: kept and waiting", DeliveryStatus.WAITING, core.history.message(message.id)!!.status)
        assertEquals(1, radio.broadcasts.size)
        main { radio.connected(false); radio.connected(true) }
        Views.waitUntil { radio.broadcasts.size == 2 }
        assertEquals("each try is a new write", 2, radio.broadcasts.map { it.packetId }.toSet().size)
        assertArrayEquals("the same packet, the same bytes", first.payload, radio.broadcasts.last().payload)
        // Every resend gave up: the picture failed, and sending it again offers the very same stored packets.
        val kept = main { core.history.outbox(bea.account).map { it.packet.toList() } }
        main {
            core.history.outbox(bea.account).forEach { core.history.setOutboxState(it.id, "FAILED") }
            core.history.setStatus(message.id, DeliveryStatus.FAILED)
        }
        assertTrue(main { core.resend(message.id) })
        assertEquals("the same picture, same id", kept, main { core.history.outbox(bea.account).map { it.packet.toList() } })
    }

    /** Our own radio going away is not the other radio's silence: the picture waits, then starts again. */
    @Test fun aLostRadioMakesThePictureWaitThenItStartsAgain() {
        TestCore.install()
        val (_, chat) = PersonFixture.meet("Kofi", accept = false)
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) }
        val message = checkNotNull(core.history.lastMessage(chat))
        val first = radio.broadcasts.single().payload
        main { radio.connected(false); radio.deliver(radio.broadcasts.last().packetId, DeliveryState.UNCONFIRMED) }
        assertEquals(1, radio.broadcasts.size)
        assertEquals("waiting for a radio, not failed", DeliveryStatus.WAITING, core.history.message(message.id)!!.status)
        main { radio.connected(true) }
        Views.waitUntil { radio.broadcasts.size == 2 }
        assertArrayEquals("the same packet, from its start", first, radio.broadcasts.last().payload)
    }

    @Test fun everySizeFitsItsBudgetAndTheLargestFitsThePictureFormat() {
        for (size in PictureSize.entries) {
            val picture = PictureMaker.prepare(photo(), size, grey = false)
            assertTrue("${size.name}: ${picture.bytes.size} B", picture.bytes.size <= size.budget)
            assertTrue(picture.bytes.size <= PictureWire.MAX_PICTURE_BYTES)
            assertTrue("${size.name}: ${picture.width}x${picture.height}", maxOf(picture.width, picture.height) <= size.side)
        }
    }

    @Test fun picturesAreForPrivateChatsOnly() {
        TestCore.install()
        val group = main { core.groupChatId() }
        assertFalse(core.canSendPictures(group))
        assertFalse(main { core.sendPicture(group, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) })
        assertTrue(radio.sentData.isEmpty())
    }

    /** Replies and reactions name a picture by its head's reference, which both phones know; later pieces never change it. */
    @Test fun aPictureTakesItsHeadsReferenceOnBothPhones() {
        TestCore.install()
        val (bea, chat) = PersonFixture.meet("Kofi")
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) }
        val mine = checkNotNull(core.history.lastMessage(chat))
        val head = checkNotNull(core.history.message(mine.id)!!.packetId)
        Views.waitUntil(8_000) { core.history.message(mine.id)!!.status == DeliveryStatus.SENT }
        assertEquals("later pieces do not change it", head, core.history.message(mine.id)!!.packetId)
        val theirsPicture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        val sealed = bea.postman.sendPicture(bea.awa, null,
            PictureWire.splitV2(91, PictureCodec.WEBP, theirsPicture.width, theirsPicture.height, theirsPicture.bytes, theirsPicture.sketch), 1_000)
            .map { checkNotNull(it).packet }
        sealed.forEach { PersonFixture.deliver(it) }
        val theirsFromBea = main { core.history.messages(chat).last { it.direction == Direction.IN } }
        assertEquals(AppMailStore.refNumber(org.phonemesh.core.session.Ratchet.reference(sealed[0].copyOfRange(1, 9))), theirsFromBea.packetId)
        assertNotNull(core.history.picture(theirsFromBea.id)!!.file)
        assertTrue("so it can be answered", main { core.react(theirsFromBea.id, "\uD83D\uDC4D") })
        assertEquals(MeshRadio.ACCOUNT_PORT, radio.broadcasts.last().port)
    }

    /** An old chat's picture (port 300) is received, keeps its first piece's radio id, and cannot be answered: the chat is read only. */
    @Test fun anOldChatPictureTakesItsFirstPiecesRadioIdAndCannotBeAnswered() {
        TestCore.install()

        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        val packets = PictureWire.split(91, PictureCodec.WEBP, picture.width, picture.height, picture.bytes, picture.sketch).map { PictureWire.encode(it) }
        // A piece may arrive before the first one; the id comes with the first.
        main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, packets[1], packetId = 5001) }
        main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, packets[0], packetId = 5000) }
        val theirs = checkNotNull(core.history.lastMessage(core.history.directChat("!0000bbbb", null).id))
        assertEquals(5000L, theirs.packetId)
        packets.drop(2).forEachIndexed { i, p -> main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, p, packetId = 5002L + i) } }
        assertNotNull(core.history.picture(theirs.id)!!.file)
        assertEquals(5000L, core.history.message(theirs.id)!!.packetId)
        assertFalse("an old chat is read only: no reaction goes out", main { core.react(theirs.id, "👍") })
        assertTrue(radio.sent.isEmpty() && radio.broadcasts.isEmpty())
    }

    @Test fun aWholePictureCanBeForwardedAsItIs() {
        TestCore.install()
        val (_, first) = PersonFixture.meet("Kofi")
        val (_, second) = PersonFixture.meet("Ama")
        val original = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        main { core.sendPicture(first, original) }
        val message = checkNotNull(core.history.lastMessage(first))
        assertTrue(core.canForwardPicture(message.id))
        val again = checkNotNull(main { core.preparedPicture(message.id) })
        assertArrayEquals("the same bytes, nothing re-encoded", original.bytes, again.bytes)
        assertArrayEquals(original.sketch, again.sketch)
        assertTrue(main { core.sendPicture(second, again) })
        assertEquals(MessageKind.PICTURE, core.history.lastMessage(second)!!.kind)
    }

    @Test fun aReceivedPictureShowsItsSketchThenItsPixels() {
        TestCore.install()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        val packets = PictureWire.split(77, PictureCodec.WEBP, picture.width, picture.height, picture.bytes, picture.sketch).map { PictureWire.encode(it) }
        main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, packets[0]) }
        val chat = core.history.directChat("!0000bbbb", null)
        val message = checkNotNull(core.history.lastMessage(chat.id))
        assertEquals(MessageKind.PICTURE, message.kind)
        assertNull("only the sketch so far", core.history.picture(message.id)!!.file)
        assertNotNull(main { core.pictureBitmap(message.id) })
        packets.drop(1).forEach { main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, it) } }
        val row = core.history.picture(message.id)!!
        assertNotNull(row.file)
        val shown = checkNotNull(main { core.pictureBitmap(message.id) })
        assertEquals(picture.width, shown.width)
        // A resent last packet makes no second picture.
        main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, packets.last()) }
        assertEquals(1, core.history.messages(chat.id).size)
        main { core.deleteMessage(message.id) }
        assertFalse(java.io.File(core.picturesDir, row.file!!).exists())
    }

    @Test fun broadcastAndOtherPortsAreNotPictures() {
        TestCore.install()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        val head = PictureWire.encode(PictureWire.split(5, PictureCodec.WEBP, picture.width, picture.height, picture.bytes, picture.sketch)[0])
        main { radio.receiveData("!0000bbbb", 301, head); radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, head, channel = 2) }
        main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, byteArrayOf(0x10, 1, 2)) }
        assertTrue(core.history.chats().none { it.kind == ChatKind.DIRECT })
    }
}
