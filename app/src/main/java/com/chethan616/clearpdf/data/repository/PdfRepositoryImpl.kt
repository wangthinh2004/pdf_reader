package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.data.mapper.toInvisibleWord
import com.chethan616.clearpdf.data.mapper.toPdfDocument
import com.chethan616.clearpdf.data.mapper.toQuality
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.model.WordConversion
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.kyant.pdfcore.compressor.PdfCompressor
import com.kyant.pdfcore.converter.PdfConverter
import com.kyant.pdfcore.searchable.PdfSearchableStamper
import com.kyant.pdfcore.security.PdfSecurityService
import com.kyant.pdfcore.viewer.PdfViewer
import com.kyant.pdfcore.word.PdfToWordConverter
import com.kyant.pdfcore.word.RecognizedWord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

class PdfRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compressor: PdfCompressor,
    private val converter: PdfConverter,
    private val viewer: PdfViewer,
    @PdfDispatcher private val pdf: CoroutineDispatcher
) : PdfRepository {

    override suspend fun estimateCompressedSize(source: DocumentInfo, level: CompressionLevel): Long =
        withContext(pdf) { compressor.estimateSize(source.toPdfDocument(), level.toQuality()) }

    override suspend fun compress(source: DocumentInfo, level: CompressionLevel, output: OutputDocument): Long =
        withContext(pdf) {
            compressor.compress(context, source.toPdfDocument(), level.toQuality(), output.ref.toUri()).sizeBytes
        }

    override suspend fun extractText(source: DocumentInfo): String =
        withContext(pdf) { converter.extractText(context, source.toPdfDocument()) }

    override suspend fun pageCount(source: DocumentInfo): Int = withContext(pdf) {
        val document = runCatching { viewer.open(context, source.ref.toUri()) }.getOrNull() ?: return@withContext 0
        try {
            document.pageCount.coerceAtLeast(0)
        } finally {
            runCatching { viewer.close(document) }
        }
    }

    override suspend fun stampSearchableText(
        source: DocumentInfo,
        output: OutputDocument,
        wordsByPage: Map<Int, List<OcrWord>>
    ) {
        withContext(pdf) {
            PdfSearchableStamper.stamp(
                context,
                source.ref.toUri(),
                output.ref.toUri(),
                wordsByPage.mapValues { (_, words) -> words.map { it.toInvisibleWord() } }
            )
        }
    }

    override suspend fun convertToWord(
        source: DocumentInfo,
        output: OutputDocument,
        recognize: suspend (pageIndex: Int) -> List<OcrWord>,
        onProgress: (page: Int, total: Int) -> Unit
    ): WordConversion = withContext(pdf) {
        val session = try {
            PdfToWordConverter.open(context, source.ref.toUri(), source.name.substringBeforeLast('.'))
        } catch (e: PdfSecurityService.PasswordRequiredException) {
            throw AppError.PasswordProtected(e)
        }
        session.use { s ->
            for (page in 0 until s.pageCount) {
                ensureActive()
                onProgress(page, s.pageCount)
                if (s.readPage(page)) {
                    val words = recognize(page)
                    s.supplyRecognizedText(page, words.map { RecognizedWord(it.text, it.left, it.top, it.right, it.bottom) })
                }
            }
            onProgress(s.pageCount, s.pageCount)
            val counter = CountingStream()
            val summary = context.contentResolver.openOutputStream(output.ref.toUri(), "w")?.use { out ->
                s.write(counter.wrap(out))
            } ?: throw AppError.Storage()
            WordConversion(summary.pages, summary.recognizedPages, summary.tables, summary.pictures, counter.count)
        }
    }

    /** Counts what goes through, since a SAF document's size isn't known until it's closed. */
    private class CountingStream {
        var count = 0L
            private set

        fun wrap(out: java.io.OutputStream) = object : java.io.FilterOutputStream(out) {
            override fun write(b: Int) { out.write(b); count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        }
    }
}
