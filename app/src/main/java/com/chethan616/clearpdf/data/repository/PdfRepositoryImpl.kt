package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.PdfDispatcher
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.data.mapper.toInvisibleWord
import com.chethan616.clearpdf.data.mapper.toPdfDocument
import com.chethan616.clearpdf.data.mapper.toQuality
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.kyant.pdfcore.compressor.PdfCompressor
import com.kyant.pdfcore.converter.PdfConverter
import com.kyant.pdfcore.searchable.PdfSearchableStamper
import com.kyant.pdfcore.viewer.PdfViewer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
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
}
