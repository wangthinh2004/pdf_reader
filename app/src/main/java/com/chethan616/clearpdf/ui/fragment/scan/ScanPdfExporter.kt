package com.chethan616.clearpdf.ui.fragment.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.chethan616.clearpdf.data.model.ScanFilter
import com.chethan616.clearpdf.data.repository.SaveLocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Turns scanned page images into one PDF, applying the chosen [ScanFilter]. */
object ScanPdfExporter {

    /** Writes the PDF to the custom save folder, or Downloads. Null when anything fails. */
    suspend fun save(context: Context, uris: List<Uri>, filter: ScanFilter): Uri? = withContext(Dispatchers.IO) {
        try {
            val pdf = PdfDocument()
            uris.forEachIndexed { index, uri ->
                val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: return@forEachIndexed
                val bitmap = applyFilter(original, filter)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create())
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdf.finishPage(page)
                if (bitmap !== original) bitmap.recycle()
                original.recycle()
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val out = outputUri(context, "ClearPDF_Scan_$stamp.pdf")
            context.contentResolver.openOutputStream(out)?.use { pdf.writeTo(it) }
                ?: throw IllegalStateException("Unable to open scan output stream")
            pdf.close()
            out
        } catch (e: Exception) {
            null
        }
    }

    /** The colour matrix behind each filter; null for the untouched original. */
    fun matrix(filter: ScanFilter): ColorMatrix? = when (filter) {
        ScanFilter.ORIGINAL -> null
        ScanFilter.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
        // High-contrast black & white: desaturate, then push contrast.
        ScanFilter.BLACK_WHITE -> ColorMatrix(
            floatArrayOf(
                2f, 0f, 0f, 0f, -180f,
                0f, 2f, 0f, 0f, -180f,
                0f, 0f, 2f, 0f, -180f,
                0f, 0f, 0f, 1f, 0f
            )
        ).apply { preConcat(ColorMatrix().apply { setSaturation(0f) }) }
        // Auto enhance: a little brighter and more contrast.
        ScanFilter.AUTO -> ColorMatrix(
            floatArrayOf(
                1.2f, 0f, 0f, 0f, 10f,
                0f, 1.2f, 0f, 0f, 10f,
                0f, 0f, 1.2f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // Vivid: more saturation, slightly brighter.
        ScanFilter.COLOR -> ColorMatrix(
            floatArrayOf(
                1.1f, 0f, 0f, 0f, 5f,
                0f, 1.1f, 0f, 0f, 5f,
                0f, 0f, 1.1f, 0f, 5f,
                0f, 0f, 0f, 1f, 0f
            )
        ).apply { preConcat(ColorMatrix().apply { setSaturation(1.5f) }) }
    }

    private fun applyFilter(src: Bitmap, filter: ScanFilter): Bitmap {
        val cm = matrix(filter) ?: return src
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return output
    }

    private fun outputUri(context: Context, fileName: String): Uri {
        SaveLocationManager.getSaveUri(context)?.let { tree ->
            runCatching { DocumentFile.fromTreeUri(context, tree)?.createFile("application/pdf", fileName)?.uri }
                .getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create scan output in Downloads")
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName).apply { if (!exists()) createNewFile() }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }
}
