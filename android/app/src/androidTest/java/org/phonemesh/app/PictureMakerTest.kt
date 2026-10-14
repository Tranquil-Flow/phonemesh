package org.phonemesh.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import kotlin.math.max

/** Reading a photo the person took or chose: the step that once refused every photo. */
@RunWith(AndroidJUnit4::class)
class PictureMakerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun jpeg(out: OutputStream, width: Int, height: Int) = out.use {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 120, 40)) }
            .compress(Bitmap.CompressFormat.JPEG, 90, it)
    }

    @Test fun aLargePhotoIsReadAlreadyShrunk() {
        val file = File(context.cacheDir, "test-photo.jpg")
        try {
            jpeg(file.outputStream(), 2400, 1600)
            val bitmap = checkNotNull(PictureMaker.load(context.contentResolver, Uri.fromFile(file))) { "a plain JPEG must be readable" }
            assertTrue("read at 512 px or more, under twice that", max(bitmap.width, bitmap.height) in 512 until 1024)
            val picture = PictureMaker.prepare(bitmap, PictureSize.MEDIUM, grey = false, sketchOnly = false)
            assertTrue("${picture.bytes.size} B q${picture.quality} ${picture.width}x${picture.height} webp0=${PictureMaker.webp(bitmap, 0).size} webp50=${PictureMaker.webp(bitmap, 50).size}", picture.bytes.size <= PictureSize.MEDIUM.budget)
        } finally {
            file.delete()
        }
    }

    @Test fun aFileThatIsNotAPhotoGivesNothing() {
        val file = File(context.cacheDir, "not-a-photo.jpg")
        try {
            file.writeText("hello")
            assertNull(PictureMaker.load(context.contentResolver, Uri.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    @Test fun theCameraWritesIntoTheAppsOwnFileAndThePhotoReadsBack() {
        val uri = CaptureProvider.uri(context)
        CaptureProvider.fileIn(context).delete()
        try {
            jpeg(checkNotNull(context.contentResolver.openOutputStream(uri)), 800, 600)
            assertTrue(CaptureProvider.fileIn(context).length() > 0)
            assertNotNull(PictureMaker.load(context.contentResolver, uri))
        } finally {
            CaptureProvider.fileIn(context).delete()
        }
    }

    @Test fun theCameraFileServesNothingElse() {
        val other = Uri.parse("content://${context.packageName}.capture/history.db")
        try {
            context.contentResolver.openInputStream(other)?.close()
            fail("only the photo file may be served")
        } catch (expected: FileNotFoundException) {
        }
    }
}
