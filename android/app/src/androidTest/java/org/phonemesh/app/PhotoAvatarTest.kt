package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.phonemesh.core.account.Avatar
import org.phonemesh.core.account.AvatarPhotos
import org.phonemesh.core.account.Profile
import org.phonemesh.core.wire.Body
import java.io.File

/**
 * Photo avatars (decision 50 D): a chosen photo is at most 64 px and 1 KB, goes to each contact once, behind the next
 * message, as private packets; a contact's photo shows only when its pieces check out against their profile.
 */
@RunWith(AndroidJUnit4::class)
class PhotoAvatarTest {
    private val core get() = TestCore.core
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "avatar-test.png")

    @After fun tearDown() {
        file.delete()
        TestCore.uninstall()
    }

    private fun picture(): Uri {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(0xFF1E88E5.toInt())
            drawCircle(150f, 100f, 70f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDD835.toInt() })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    private fun send(chatId: Long, text: String) {
        val before = TestCore.radio.broadcasts.size
        TestCore.onMain { core.setDraft(chatId, text); assertTrue(core.send(chatId)) }
        Views.waitUntil { TestCore.radio.broadcasts.size > before }
        Thread.sleep(500)
    }

    @Test fun aChosenPhotoGoesOnceBehindTheNextMessage() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val draft = core.prepareAvatar(picture())!!
        assertTrue("square, at most 64 px", draft.width == draft.height && draft.width <= AvatarPhotos.MAX_SIDE)
        assertTrue("at most 1 KB: ${draft.webp.size}", draft.webp.size <= AvatarPhotos.MAX_BYTES)
        assertTrue(TestCore.onMain { core.saveProfilePhoto("Awa", 0x1E88E5, draft) })
        assertEquals(draft.avatar, core.ownAvatar())
        assertNotNull("this phone shows its photo", core.ownFace()!!.photo)
        assertEquals("saving sends nothing by itself", 0, TestCore.radio.broadcasts.size)

        send(chatId, "hello with my photo")
        val bodies = TestCore.radio.broadcasts.mapNotNull { bea.postman.receive(it.payload, 1_000)?.body }
        assertTrue(bodies.first() is Body.Text)
        val pieces = bodies.filterIsInstance<Body.AvatarPiece>().map { it.bytes }
        assertArrayEquals("the photo they get is the one saved", draft.webp, AvatarPhotos.join(pieces)!!.webp)
        assertEquals("one head and ceil(size / 198) parts", 1 + (draft.webp.size + 197) / 198, pieces.size)

        val after = TestCore.radio.broadcasts.size
        send(chatId, "nothing new")
        val later = TestCore.radio.broadcasts.drop(after).mapNotNull { bea.postman.receive(it.payload, 2_000)?.body }
        assertTrue("sent once", later.none { it is Body.AvatarPiece })

        // A different photo the same day is refused, with a sentence.
        val other = core.draftOf(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF43A047.toInt()) })!!
        assertFalse(TestCore.onMain { core.photoChangeAllowed() })
        assertFalse(TestCore.onMain { core.saveProfilePhoto("Awa", 0x1E88E5, other) })
        assertEquals(draft.avatar, core.ownAvatar())
    }

    /** The name screen's Photo choice: the system picker's photo, shown as the preview, saved with the name. */
    @Test fun thePhotoChoiceOnTheNameScreenSavesASmallPhoto() {
        TestCore.install()
        val inst = InstrumentationRegistry.getInstrumentation()
        val monitor = inst.addMonitor(android.content.IntentFilter(android.content.Intent.ACTION_GET_CONTENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            addDataType("image/*")
        }, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(picture())), true)
        try {
            val screen = Views.start(NameActivity::class.java)
            val tile = Views.tagged(screen, "picture_photo")!!
            assertEquals("Photo", tile.contentDescription.toString())
            Views.tap(screen, "picture_photo")
            val end = System.currentTimeMillis() + 10_000
            while (!TestCore.onMain { tile.isSelected }) { check(System.currentTimeMillis() < end) { "photo not chosen" }; Thread.sleep(100) }
            assertTrue(A11yCheck.problems(screen).toString(), A11yCheck.problems(screen).isEmpty())
            Views.tap(screen, "name_save")
            Views.waitUntil { screen.isFinishing }
            val saved = core.ownAvatar() as Avatar.Photo
            val own = TestCore.onMain { core.history.ownPhoto()!! }
            assertArrayEquals(AvatarPhotos.id(own.webp), saved.id)
            assertTrue(own.webp.size <= AvatarPhotos.MAX_BYTES && own.width <= AvatarPhotos.MAX_SIDE)
        } finally {
            inst.removeMonitor(monitor)
        }
    }

    @Test fun aContactsPhotoShowsOnlyWhenItsPiecesCheckOut() {
        TestCore.install()
        val (bea, chatId) = PersonFixture.meet()
        val photo = core.draftOf(Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEC407A.toInt()) })!!
        val chat = TestCore.onMain { core.history.chat(chatId)!! }
        PersonFixture.deliver(PersonFixture.send(bea, Body.ProfileUpdate(Profile("Bea", 0x8E24AA, photo.avatar, revision = 2))))
        assertEquals(photo.avatar, core.chatFace(chat)!!.avatar)
        assertNull("the letter until the photo arrives", core.chatFace(chat)!!.photo)

        val pieces = AvatarPhotos.pieces(photo.webp, photo.width, photo.height, photo.sketch)
        for (piece in pieces.dropLast(1)) PersonFixture.deliver(PersonFixture.send(bea, Body.AvatarPiece(piece), 2_000))
        assertNull("not all pieces yet", core.chatFace(chat)!!.photo)
        PersonFixture.deliver(PersonFixture.send(bea, Body.AvatarPiece(pieces.last()), 3_000))
        Views.waitUntil { core.chatFace(chat)!!.photo != null }
        assertNull("a photo is not a message", TestCore.onMain { core.history.lastMessage(chatId) })

        // A photo that is not the one the profile names never shows.
        val wrong = core.draftOf(Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) })!!
        PersonFixture.deliver(PersonFixture.send(bea, Body.ProfileUpdate(Profile("Bea", 0x8E24AA, Avatar.Photo(AvatarPhotos.id(byteArrayOf(1))), revision = 3)), 4_000))
        for (piece in AvatarPhotos.pieces(wrong.webp, wrong.width, wrong.height, wrong.sketch)) {
            PersonFixture.deliver(PersonFixture.send(bea, Body.AvatarPiece(piece), 5_000))
        }
        assertNull(core.chatFace(chat)!!.photo)
    }
}
