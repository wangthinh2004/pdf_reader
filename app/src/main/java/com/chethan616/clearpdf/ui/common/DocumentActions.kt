package com.chethan616.clearpdf.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.chethan616.clearpdf.R
import java.io.File

/** Share / open-with / delete for a document the user picked from a list. */
object DocumentActions {

    /** A file:// uri can't be handed to other apps, so it goes through the FileProvider first. */
    fun exportUri(context: Context, uri: Uri): Uri =
        if (uri.scheme == "file") {
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.provider", File(uri.path!!))
            }.getOrNull() ?: uri
        } else uri

    fun share(context: Context, uri: Uri) {
        val shareUri = exportUri(context, uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(shareUri) ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.recents_share_pdf))) }
    }

    fun openWith(context: Context, uri: Uri) {
        val viewUri = exportUri(context, uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(viewUri, context.contentResolver.getType(viewUri) ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, context.getString(R.string.recents_open_with))) }
    }

    /** True when [uri]'s provider lets us delete the underlying file (not just the recents entry). */
    fun canDelete(context: Context, uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            "file" -> File(uri.path ?: return false).let { it.isFile && it.parentFile?.canWrite() == true }
            "content" -> {
                if (!DocumentsContract.isDocumentUri(context, uri)) return false
                context.contentResolver.query(
                    uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
                )?.use { c ->
                    c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                } ?: false
            }
            else -> false
        }
    }.getOrDefault(false)

    /** Deletes the file behind [uri]. Returns false (never throws) when the provider refuses. */
    fun delete(context: Context, uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            "file" -> File(uri.path!!).delete()
            "content" -> DocumentsContract.deleteDocument(context.contentResolver, uri)
            else -> false
        }
    }.getOrDefault(false)

    fun openExternalLink(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
