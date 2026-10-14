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
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false, sketchOnly = false)
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
        assertTrue(main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.SMALL, grey = false, sketchOnly = false)) })
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
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.SMALL, grey = false, sketchOnly = false)) }
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

    @Test fun aLostRadioEndsThePicture() {
        TestCore.install()
        val chat = main { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        main { core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.SMALL, grey = false, sketchOnly = false)) }
        val message = checkNotNull(core.history.lastMessage(chat))
        main { radio.connected(false); radio.deliver(radio.sentData.last().packetId, DeliveryState.UNCONFIRMED) }
        assertEquals(1, radio.sentData.size)
        assertEquals(DeliveryStatus.FAILED, core.history.message(message.id)!!.status)
    }

    @Test fun picturesAreForPrivateChatsOnly() {
        TestCore.install()
        val group = main { core.groupChatId() }
        assertFalse(core.canSendPictures(group))
        assertFalse(main { core.sendPicture(group, PictureMaker.prepare(photo(), PictureSize.SMALL, grey = false, sketchOnly = false)) })
        assertTrue(radio.sentData.isEmpty())
    }

    @Test fun aReceivedPictureShowsItsSketchThenItsPixels() {
        TestCore.install()
        val picture = PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false, sketchOnly = false)
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
        val picture = PictureMaker.prepare(photo(), PictureSize.SMALL, grey = false, sketchOnly = false)
        val head = PictureWire.encode(PictureWire.split(5, PictureCodec.WEBP, picture.width, picture.height, picture.bytes, picture.sketch)[0])
        main { radio.receiveData("!0000bbbb", 301, head); radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, head, channel = 2) }
        main { radio.receiveData("!0000bbbb", MeshRadio.PICTURE_PORT, byteArrayOf(0x10, 1, 2)) }
        assertTrue(core.history.chats().none { it.kind == ChatKind.DIRECT })
    }
}
