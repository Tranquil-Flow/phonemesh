package org.phonemesh.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Read-only provider for the files `PackSharing.prepare` puts in `cacheDir/share`; nothing else. */
class ShareProvider : ContentProvider() {
    private val namePattern = Regex("^[A-Za-z0-9._-]{1,120}$")

    override fun onCreate() = true

    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val name = uri.lastPathSegment?.takeIf { namePattern.matches(it) && !it.startsWith('.') }
            ?: throw FileNotFoundException("Bad name")
        val dir = File(ctx.cacheDir, "share").canonicalFile
        val file = File(dir, name)
        if (!file.canonicalPath.startsWith(dir.path + File.separator) || !file.isFile) throw FileNotFoundException("Not shared")
        return file
    }

    override fun getType(uri: Uri): String? = when {
        uri.lastPathSegment?.endsWith(".apk") == true -> "application/vnd.android.package-archive"
        uri.lastPathSegment?.endsWith(".zip") == true -> "application/zip"
        uri.lastPathSegment?.endsWith(".png") == true -> "image/png"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Read only" }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor {
        val file = resolve(uri)
        val columns = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
