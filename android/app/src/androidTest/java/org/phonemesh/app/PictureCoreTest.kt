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

    @Test fun aPictureGoesOutOnePacketAtATimeAndIsDeliveredWhenAllAreAcknowledged() {
        TestCore.install()
        val chat = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)
        assertTrue(picture.bytes.size <= PictureSize.MEDIUM.budget)
        assertTrue(main { core.sendPicture(chat, picture) })
        val message = checkNotNull(core.history.lastMessage(chat))
        assertEquals(MessageKind.PICTURE, message.kind)
        assertEquals(DeliveryStatus.WAITING, message.status)
        val total = PictureWire.partsFor(picture.bytes.size) + 1
        for (i in 0 until total) {
            assertEquals("only one packet in flight", i + 1, radio.sentData.size)
            val packet = radio.sentData.last()
            assertEquals(MeshRadio.PICTURE_PORT, packet.port)
            assertEquals("!0000aaaa", packet.destinationId)
            main { radio.deliver(packet.packetId, DeliveryState.QUEUED) }
            assertEquals("queued is not enough", i + 1, radio.sentData.size)
            main { radio.deliver(packet.packetId, DeliveryState.ACKNOWLEDGED) }
        }
        assertEquals(total, radio.sentData.size)
        assertEquals(DeliveryStatus.DELIVERED, core.history.message(message.id)!!.status)
        assertEquals(total, core.history.picture(message.id)!!.have)
        assertTrue(radio.sent.isEmpty())
    }

    @Test fun aPictureWithoutARadioWaitsVisiblyAndGoesWhenOneIsReady() {
        TestCore.install(connected = false)
        val chat = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        assertTrue(main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) })
        val message = checkNotNull(core.history.lastMessage(chat))
        assertTrue("the bubble can say it waits for a radio", core.pictureWaitsForRadio(message.id))
        assertEquals(DeliveryStatus.QUEUED, message.status)
        assertTrue(radio.sentData.isEmpty())
        main { radio.connected(true) }
        assertFalse(core.pictureWaitsForRadio(message.id))
        assertEquals("the first packet goes once a radio is ready", 1, radio.sentData.size)
    }

    @Test fun aPacketIsTriedThreeTimesThenThePictureFailsAndCanBeSentAgain() {
        TestCore.install()
        val chat = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) }
        val message = checkNotNull(core.history.lastMessage(chat))
        main { radio.deliver(radio.sentData.last().packetId, DeliveryState.UNCONFIRMED) }
        main { radio.deliver(radio.sentData.last().packetId, DeliveryState.FAILED) }
        assertEquals(3, radio.sentData.size)
        assertEquals("each try is a new packet", 3, radio.sentData.map { it.packetId }.toSet().size)
        assertTrue(radio.sentData.all { it.payload.contentEquals(radio.sentData[0].payload) })
        main { radio.deliver(radio.sentData.last().packetId, DeliveryState.FAILED) }
        assertEquals(3, radio.sentData.size)
        assertEquals(DeliveryStatus.FAILED, core.history.message(message.id)!!.status)
        assertTrue(main { core.resend(message.id) })
        assertEquals(4, radio.sentData.size)
        assertArrayEquals("the same picture, same id", radio.sentData[0].payload, radio.sentData[3].payload)
    }

    /** Our own radio going away is not the other radio's silence: the picture waits, then starts again. */
    @Test fun aLostRadioMakesThePictureWaitThenItStartsAgain() {
        TestCore.install()
        val chat = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) }
        val message = checkNotNull(core.history.lastMessage(chat))
        val first = radio.sentData.single().payload
        main { radio.connected(false); radio.deliver(radio.sentData.last().packetId, DeliveryState.UNCONFIRMED) }
        assertEquals(1, radio.sentData.size)
        assertEquals("waiting for a radio, not failed", DeliveryStatus.QUEUED, core.history.message(message.id)!!.status)
        assertTrue(core.pictureWaitsForRadio(message.id))
        main { radio.connected(true) }
        assertEquals(2, radio.sentData.size)
        assertArrayEquals("the same picture, same id, from its start", first, radio.sentData.last().payload)
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

    /** Replies and reactions name a picture by its first piece's radio id, which both phones know. */
    @Test fun aPictureTakesItsFirstPiecesRadioIdOnBothPhones() {
        TestCore.install()
        val chat = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)) }
        val mine = checkNotNull(core.history.lastMessage(chat))
        assertNull("not before the other radio confirms it", core.history.message(mine.id)!!.packetId)
        val head = radio.sentData.first().packetId
        main { radio.deliver(head, DeliveryState.ACKNOWLEDGED) }
        assertEquals(head, core.history.message(mine.id)!!.packetId)
        main { radio.deliver(radio.sentData.last().packetId, DeliveryState.ACKNOWLEDGED) }
        assertEquals("later pieces do not change it", head, core.history.message(mine.id)!!.packetId)

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
        assertTrue("so it can be answered", main { core.react(theirs.id, "👍") })
        assertEquals(5000L, radio.sent.last().replyTo)
    }

    @Test fun aWholePictureCanBeForwardedAsItIs() {
        TestCore.install()
        val first = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val second = main { core.startDirectChat(RadioPeer("!0000bbbb", "Ama")) }
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
