package org.phonemesh.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * The one file the camera app writes a new photo into, in this app's cache: nothing is saved in the phone's gallery and
 * no camera permission is needed. Not exported; the camera gets a one-time grant for [uri] only.
 */
class CaptureProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        if (uri.lastPathSegment != NAME) throw FileNotFoundException("Not the photo")
        return fileIn(ctx)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val target = file(uri)
        target.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor {
        val target = file(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) target.length() else NAME }) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val NAME = "photo.jpg"

        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.capture/$NAME")

        /** The photo file; deleted once read, so no photo stays on the phone. */
        fun fileIn(context: Context): File = File(File(context.cacheDir, "capture"), NAME)
    }
}
