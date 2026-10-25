package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.ble.MeshRadio
import org.phonemesh.ble.RadioPeer
import org.phonemesh.core.DeliveryStatus
import org.phonemesh.core.PictureCodec
import org.phonemesh.core.PictureWire
import java.io.File

/** Old radio-addressed (DIRECT) chats: read only on every path, and still received. Kept apart from the person tests. */
@RunWith(AndroidJUnit4::class)
class OldChatsReadOnlyTest {
    private val core get() = TestCore.core

    @After fun tearDown() {
        Views.finish(Views.resumed())
        TestCore.uninstall()
    }

    private fun photo(): Bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 300) for (y in 0 until 200) setPixel(x, y, Color.rgb((x * 7) % 256, (y * 5) % 256, (x + y) % 256))
    }

    @Test fun oldRadioChatsAreReadOnlyOnEveryPath() {
        TestCore.install()
        val chat = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        TestCore.onMain {
            core.setDraft(chat, "hello")
            assertFalse(core.send(chat))
            assertFalse(core.plan(chat, System.currentTimeMillis() + 60_000))
            assertFalse(core.canSendPictures(chat))
            assertFalse(core.sendPicture(chat, PictureMaker.prepare(photo(), PictureSize.MEDIUM, grey = false)))
            val failed = core.history.addOutgoing(chat, "old", "fr", 1L)
            core.history.setStatus(failed.id, DeliveryStatus.FAILED)
            assertFalse(core.resend(failed.id))
            val queued = core.history.addOutgoing(chat, "queued", "fr", 2L)
            core.history.setStatus(queued.id, DeliveryStatus.QUEUED)
            core.history.addReaction(chat, null, "\uD83D\uDC4D", 77L, 3L, waiting = true)
            core.sendQueued()
            assertEquals(DeliveryStatus.FAILED, core.history.message(queued.id)!!.status)
            core.history.addPlanned(chat, "planned", "fr", 1L, null)
            core.sendDue()
            assertFalse(core.hasDuePlanned())
            val heard = core.history.addIncoming(chat, "!0000aaaa", "x", "fr", 4L, false, packetId = 77L)
            assertFalse(core.react(heard.id, "\uD83D\uDC4D"))
            val shared = core.history.addIncoming(core.groupChatId(), "!0000bbbb", "y", "fr", 5L, false)
            assertFalse(core.forward(shared.id, chat))
        }
        Views.waitUntil { core.history.queuedReactions().isEmpty() }
        assertTrue(TestCore.radio.sent.isEmpty())
        assertTrue(TestCore.radio.sentData.isEmpty())
        assertTrue(TestCore.radio.broadcasts.isEmpty())
    }

    @Test fun oldTextAndPicturesAreStillReceivedInAnOldChat() {
        TestCore.install()
        TestCore.onMain { TestCore.radio.receive("!0000aaaa", "[pm1:fr] Salut", true) }
        val packets = PictureWire.split(5, PictureCodec.WEBP, 8, 8, ByteArray(300) { it.toByte() }, byteArrayOf(1, 2, 3))
        TestCore.onMain { packets.forEach { TestCore.radio.receiveData("!0000aaaa", MeshRadio.PICTURE_PORT, PictureWire.encode(it), 0, 9L) } }
        val chat = TestCore.onMain { core.history.directChat("!0000aaaa", null) }
        val shown = TestCore.onMain { core.history.messages(chat.id) }
        assertEquals(listOf(MessageKind.TEXT, MessageKind.PICTURE), shown.map { it.kind })
        val file = TestCore.onMain { core.history.picture(shown[1].id)!!.file }
        assertNotNull(file)
        assertTrue(File(core.picturesDir, file!!).isFile)
        assertTrue(TestCore.radio.sentData.isEmpty() && TestCore.radio.broadcasts.isEmpty() && TestCore.radio.sent.isEmpty())
    }

    @Test fun anOldChatShowsNoWayToWrite() {
        TestCore.install()
        val chat = TestCore.onMain { core.startDirectChat(RadioPeer("!0000aaaa", "Kofi")) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = Views.launch<ConversationActivity>(ConversationActivity.intent(context, chat))
        try {
            Views.waitUntil { screen.window.decorView.findViewWithTag<android.view.View>("mic") != null }
            TestCore.onMain {
                for (tag in listOf("mic", "keyboard", "picture", "draft_bar")) {
                    val view = screen.window.decorView.findViewWithTag<android.view.View>(tag)
                    assertTrue("$tag must not show in an old chat", view == null || !view.isShown)
                }
            }
        } finally { Views.finish(screen) }
    }
}
