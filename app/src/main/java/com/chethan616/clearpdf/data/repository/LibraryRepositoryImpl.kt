package com.chethan616.clearpdf.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.platform.StorageAccess
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.LibraryRepository
import com.chethan616.clearpdf.utils.docKindOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlin.coroutines.resume
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Device documents come from MediaStore (content://media/… on Android 10+, file:// before that,
 * see [DeviceDocumentScanner]); files opened from a picker are SAF document URIs. Each file
 * operation handles all three.
 */
class LibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val documents: DocumentRepository
) : LibraryRepository {

    override fun access(): LibraryAccess = when {
        !StorageAccess.canListDocuments() -> LibraryAccess.UNSUPPORTED
        !StorageAccess.hasDocumentAccess(context) -> LibraryAccess.NEEDS_PERMISSION
        else -> LibraryAccess.GRANTED
    }

    override suspend fun deviceDocuments(): List<LibraryDocument> =
        DeviceDocumentScanner.scan(context).map {
            LibraryDocument(it.uri.toDocumentRef(), it.name, it.size, it.modified, it.kind)
        }

    override suspend fun rename(document: LibraryDocument, newName: String): LibraryDocument = withContext(io) {
        val uri = document.ref.toUri()
        val renamedUri: Uri = try {
            when {
                uri.scheme == "file" -> {
                    val source = File(requireNotNull(uri.path))
                    val target = File(source.parentFile, newName)
                    if (target.exists() || !source.renameTo(target)) throw AppError.Storage()
                    MediaScannerConnection.scanFile(context, arrayOf(source.path, target.path), null, null)
                    Uri.fromFile(target)
                }
                uri.authority == MediaStore.AUTHORITY -> {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
                    if (context.contentResolver.update(uri, values, null, null) == 0) throw AppError.Storage()
                    uri
                }
                DocumentsContract.isDocumentUri(context, uri) ->
                    DocumentsContract.renameDocument(context.contentResolver, uri, newName) ?: throw AppError.Storage()
                else -> throw AppError.Storage()
            }
        } catch (e: AppError) {
            throw e
        } catch (e: Exception) {
            throw AppError.Storage(e)
        }
        document.copy(ref = renamedUri.toDocumentRef(), name = newName, kind = docKindOf(newName))
    }

    override suspend fun delete(document: LibraryDocument): Boolean = withContext(io) {
        val uri = document.ref.toUri()
        runCatching {
            when {
                uri.scheme == "file" -> File(requireNotNull(uri.path)).delete().also { deleted ->
                    if (deleted) MediaScannerConnection.scanFile(context, arrayOf(uri.path), null, null)
                }
                uri.authority == MediaStore.AUTHORITY -> context.contentResolver.delete(uri, null, null) > 0
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.deleteDocument(context.contentResolver, uri)
                else -> false
            }
        }.getOrDefault(false)
    }

    override suspend fun duplicate(document: LibraryDocument): OutputDocument {
        val extension = document.name.substringAfterLast('.', "")
        val baseName = document.name.substringBeforeLast('.')
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
        val output = documents.createOutput(baseName, extension, mime)
        withContext(io) {
            try {
                val resolver = context.contentResolver
                resolver.openInputStream(document.ref.toUri()).use { input ->
                    resolver.openOutputStream(output.ref.toUri()).use { out ->
                        requireNotNull(input).copyTo(requireNotNull(out))
                    }
                }
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(output.ref.toUri(), null, null) }
                throw AppError.Storage(e)
            }
            awaitMediaScan(output.ref.toUri())
        }
        return output
    }

    /**
     * MediaStore fills in a new file's size on its own schedule, and [deviceDocuments] skips
     * zero-size rows, so a list reloaded right after a copy would miss it. Rescanning the file
     * and waiting for it makes the copy show up straight away.
     */
    private suspend fun awaitMediaScan(uri: Uri) {
        if (uri.authority != MediaStore.AUTHORITY) return
        @Suppress("DEPRECATION")
        val path = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: return
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 3_000L
    }
}
