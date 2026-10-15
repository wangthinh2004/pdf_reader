package com.chethan616.clearpdf.presentation.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.LruCache
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.utils.DocKind
import com.chethan616.clearpdf.utils.OfficeThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * First-page previews for the Home grid.
 *
 * PDFs render with the platform PdfRenderer (separate from pdf-core's viewer, so it never contends
 * with an open document). .docx/.xlsx/.pptx use the preview saved inside the file when there is a
 * usable one, else [OfficeThumbnails] draws one. Legacy .doc/.xls/.ppt and anything else keep their
 * kind icon.
 *
 * Results live in a small memory cache and on disk, keyed by file, modification time and width, so
 * an edited file gets a fresh preview and an unchanged one is never drawn twice.
 */
object DocumentThumbnails {
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Two renders at a time is enough to fill a screen of cards without starving the UI. */
    private val renders = Semaphore(2)

    /** Office files are read whole (or parsed whole), so big ones just keep their icon. */
    private const val MaxOfficeBytes = 20L * 1024 * 1024
    private const val MaxDiskFiles = 400

    fun supports(doc: LibraryDocument): Boolean = when (doc.kind) {
        DocKind.Pdf -> true
        DocKind.Word, DocKind.Excel, DocKind.Ppt -> office(doc) != null
        else -> false
    }

    fun cached(doc: LibraryDocument, widthPx: Int): Bitmap? = memory.get(key(doc, widthPx))

    /** Null when the file can't be previewed (encrypted, damaged, too big, provider refused). */
    suspend fun load(context: Context, doc: LibraryDocument, widthPx: Int): Bitmap? {
        val key = key(doc, widthPx)
        memory.get(key)?.let { return it }
        return renders.withPermit {
            withContext(Dispatchers.IO) {
                val file = File(dir(context), key.sha1() + ".webp")
                val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                    ?: runCatching { render(context, doc, widthPx) }.getOrNull()?.also { store(context, file, it) }
                bitmap?.also { memory.put(key, it) }
            }
        }
    }

    private fun render(context: Context, doc: LibraryDocument, widthPx: Int): Bitmap? {
        val uri = doc.ref.toUri()
        if (doc.kind == DocKind.Pdf) return renderPdf(context, uri, widthPx)
        val flavor = office(doc) ?: return null
        if (doc.sizeBytes > MaxOfficeBytes) return null
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { OfficeThumbnails.embedded(it, widthPx) }?.let { return it }
        return when (flavor) {
            Office.DOCX -> resolver.openInputStream(uri)?.use { OfficeThumbnails.docx(it, widthPx) }
            Office.XLSX -> resolver.openInputStream(uri)?.use { OfficeThumbnails.xlsx(it, widthPx) }
            Office.PPTX -> resolver.openInputStream(uri)?.use { it.readBytes() }?.let { OfficeThumbnails.pptx(it, widthPx) }
        }
    }

    private fun renderPdf(context: Context, uri: Uri, widthPx: Int): Bitmap? {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return fd.use {
            PdfRenderer(it).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (page.height * widthPx.toFloat() / page.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.WHITE)
                        page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private enum class Office { DOCX, XLSX, PPTX }

    private fun office(doc: LibraryDocument): Office? = when (doc.name.substringAfterLast('.', "").lowercase()) {
        "docx" -> Office.DOCX
        "xlsx" -> Office.XLSX
        "pptx" -> Office.PPTX
        else -> null
    }

    private fun dir(context: Context) = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    @Suppress("DEPRECATION") // WEBP_LOSSY needs API 30.
    private fun store(context: Context, file: File, bitmap: Bitmap) {
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 85, it) }
            val files = dir(context).listFiles() ?: return
            if (files.size > MaxDiskFiles) {
                files.sortedBy { it.lastModified() }.take(files.size - MaxDiskFiles).forEach { it.delete() }
            }
        }
    }

    private fun key(doc: LibraryDocument, widthPx: Int) = "$widthPx|${doc.modifiedAt}|${doc.sizeBytes}|${doc.ref}"

    private fun String.sha1(): String =
        MessageDigest.getInstance("SHA-1").digest(toByteArray()).joinToString("") { "%02x".format(it) }
}
