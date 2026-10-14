package org.phonemesh.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** A picture leaving the app on the person's request: into the phone's Photos, or to another app through Share. */
object PictureExport {
    private const val TAG = "PhonemeshPicture"

    /** Photos takes a picture without any permission from Android 10 on; before that it would need storage access. */
    val canSave: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Saves [bitmap] as a JPEG in Pictures/Phonemesh, dated now so galleries put it first; null when Android refused.
     * The returned address opens it in the phone's gallery ([view]).
     */
    fun save(context: Context, bitmap: Bitmap): Uri? {
        if (!canSave) return null
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "phonemesh-$now.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Phonemesh")
            put(MediaStore.Images.Media.DATE_TAKEN, now)
            put(MediaStore.Images.Media.DATE_ADDED, now / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, now / 1000)
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }
            .onFailure { Log.w(TAG, "Photos refused the picture", it) }.getOrNull() ?: return null
        return try {
            checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            Log.i(TAG, "picture saved to Photos: $uri ${bitmap.width}x${bitmap.height}")
            uri
        } catch (e: Exception) {
            Log.w(TAG, "picture not saved", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Opens a saved picture in the phone's gallery; false when no app can show it. */
    fun view(context: Context, uri: Uri): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: Exception) {
        Log.w(TAG, "no gallery for the picture", e)
        false
    }

    /** Hands [bitmap] to another app through Android's share sheet, from the app's share folder (ShareProvider). */
    fun share(context: Context, bitmap: Bitmap): Boolean = try {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "phonemesh-picture.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = Uri.parse("content://${PackSharing.authority(context)}/${file.name}")
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: Exception) {
        Log.w(TAG, "picture not shared", e)
        false
    }
}
