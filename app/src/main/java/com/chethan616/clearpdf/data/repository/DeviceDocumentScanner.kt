package com.chethan616.clearpdf.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.chethan616.clearpdf.utils.DocKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceDocument(
    val uri: Uri,
    val name: String,
    val size: Long,
    /** Epoch millis. */
    val modified: Long,
    val folder: String,
    val kind: DocKind
)

/** Lists documents on shared storage through MediaStore. Needs [StorageAccess.hasDocumentAccess]. */
object DeviceDocumentScanner {

    suspend fun scan(context: Context): List<DeviceDocument> = withContext(Dispatchers.IO) {
        val filesUri = MediaStore.Files.getContentUri("external")
        val hasRelative = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            @Suppress("DEPRECATION") add(MediaStore.Files.FileColumns.DATA)
            if (hasRelative) add(MediaStore.Files.FileColumns.RELATIVE_PATH)
        }.toTypedArray()
        // Name test, not MIME: office files are often indexed with a null or generic MIME type.
        // Before Android 10 DISPLAY_NAME is null for non-media files, so match on the path instead.
        @Suppress("DEPRECATION")
        val nameColumn = if (hasRelative) MediaStore.Files.FileColumns.DISPLAY_NAME else MediaStore.Files.FileColumns.DATA
        val selection = DeviceDocumentFilter.EXTENSIONS.joinToString(" OR ", "(", ")") {
            "LOWER($nameColumn) LIKE ?"
        } + " AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        val args = DeviceDocumentFilter.EXTENSIONS.map { "%.$it" }.toTypedArray()

        val rows = ArrayList<MediaRow>()
        context.contentResolver.query(filesUri, projection, selection, args, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val size = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            @Suppress("DEPRECATION") val data = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val rel = if (hasRelative) c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH) else -1
            while (c.moveToNext()) {
                rows += MediaRow(
                    id = c.getLong(id),
                    name = c.getString(name),
                    size = c.getLong(size),
                    modifiedSec = c.getLong(modified),
                    relativePath = if (rel >= 0) c.getString(rel) else null,
                    dataPath = if (data >= 0) c.getString(data) else null
                )
            }
        }
        val pathById = if (hasRelative) emptyMap() else rows.associate { it.id to it.dataPath }
        DeviceDocumentFilter.select(rows).map {
            // Before Android 10 the viewers can't resolve a name from a MediaStore file uri (its
            // DISPLAY_NAME is null), so hand them the file itself, whose last segment is the name.
            val uri = pathById[it.id]?.let { path -> Uri.fromFile(java.io.File(path)) }
                ?: ContentUris.withAppendedId(filesUri, it.id)
            DeviceDocument(uri, it.name, it.size, it.modifiedMs, it.folder, it.kind)
        }
    }
}
