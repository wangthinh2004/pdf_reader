package com.chethan616.clearpdf.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : DocumentRepository {

    override suspend fun open(ref: DocumentRef): DocumentInfo = withContext(io) {
        val uri = ref.toUri()
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        DocumentInfo(ref = ref, name = displayName(uri) ?: DEFAULT_NAME, sizeBytes = size(uri))
    }

    override suspend fun createOutput(prefix: String, extension: String, mimeType: String): OutputDocument =
        withContext(io) {
            val name = "${prefix}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$extension"
            val uri = try {
                createInSaveLocation(name, mimeType) ?: createInDownloads(name, mimeType)
            } catch (e: Exception) {
                throw AppError.Storage(e)
            }
            OutputDocument(uri.toDocumentRef(), name, SaveLocationManager.getSavePathDisplay(context))
        }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun size(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: -1L

    /** The user's chosen folder; null (-> Downloads) when unset or no longer writable. */
    private fun createInSaveLocation(name: String, mimeType: String): Uri? {
        val tree = SaveLocationManager.getSaveUri(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, tree)?.createFile(mimeType, name)?.uri }.getOrNull()
    }

    private fun createInDownloads(name: String, mimeType: String): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create output in Downloads")
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            dir.mkdirs()
            val file = File(dir, name).apply { if (!exists()) createNewFile() }
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }

    private companion object {
        const val DEFAULT_NAME = "Unknown.pdf"
    }
}
