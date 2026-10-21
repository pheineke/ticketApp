package de.heineke.myvrnlite.data

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
 * Hands saved invoices (Repo.invoiceFile) to a PDF viewer, read-only and only by a granted
 * content:// URI — a minimal FileProvider, since the app has no AndroidX.
 * URI: content://<authority>/<orderId>/<display name>.pdf
 */
class InvoiceProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: throw FileNotFoundException(uri.toString())
        return File(File(context!!.filesDir, "invoices"), "$id.pdf").takeIf { it.isFile } ?: throw FileNotFoundException(uri.toString())
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/pdf"

    /** Name and size, which viewers show and use when saving a copy. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) uri.lastPathSegment else null })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        /** As android:authorities in the manifest: <applicationId>.invoices. */
        fun uri(context: Context, orderId: Long, name: String): Uri = Uri.Builder().scheme("content")
            .authority(context.packageName + ".invoices").appendPath(orderId.toString()).appendPath("$name.pdf").build()
    }
}
