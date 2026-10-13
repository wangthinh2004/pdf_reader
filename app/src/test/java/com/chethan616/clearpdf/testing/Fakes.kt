package com.chethan616.clearpdf.testing

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import com.chethan616.clearpdf.domain.repository.SummaryRepository

val SAMPLE_REF = DocumentRef("content://docs/sample.pdf")
val SAMPLE_DOC = DocumentInfo(SAMPLE_REF, "sample.pdf", 200 * 1024L)

class FakeDocumentRepository : DocumentRepository {
    var openError: Throwable? = null
    val createdPrefixes = mutableListOf<String>()
    override suspend fun open(ref: DocumentRef): DocumentInfo {
        openError?.let { throw it }
        return DocumentInfo(ref, "sample.pdf", 200 * 1024L)
    }
    override suspend fun createOutput(prefix: String, extension: String, mimeType: String): OutputDocument {
        createdPrefixes += prefix
        return OutputDocument(DocumentRef("content://out/$prefix.$extension"), "$prefix.$extension", "Downloads (default)")
    }
}

class FakeSettingsRepository(var fraction: Float = 0.7f) : SettingsRepository {
    override fun defaultCompressionFraction() = fraction
}

class FakeRecentFilesRepository : RecentFilesRepository {
    val added = mutableListOf<Pair<OutputDocument, Long>>()
    override suspend fun add(document: OutputDocument, sizeBytes: Long) { added += document to sizeBytes }
}

class FakeEngagementRepository : EngagementRepository {
    var interactions = 0
    override suspend fun recordPdfInteraction() { interactions++ }
}

class FakePdfRepository : PdfRepository {
    var estimates = mapOf(CompressionLevel.LOW to 50 * 1024L, CompressionLevel.MEDIUM to 100 * 1024L, CompressionLevel.HIGH to 150 * 1024L)
    var compressedBytes = 100 * 1024L
    var compressError: Throwable? = null
    var text = ""
    var pages = 0
    var pageCountError: Throwable? = null
    val stamped = mutableListOf<Map<Int, List<OcrWord>>>()
    override suspend fun estimateCompressedSize(source: DocumentInfo, level: CompressionLevel) = estimates.getValue(level)
    override suspend fun compress(source: DocumentInfo, level: CompressionLevel, output: OutputDocument): Long {
        compressError?.let { throw it }
        return compressedBytes
    }
    override suspend fun extractText(source: DocumentInfo) = text
    override suspend fun pageCount(source: DocumentInfo): Int {
        pageCountError?.let { throw it }
        return pages
    }
    override suspend fun stampSearchableText(source: DocumentInfo, output: OutputDocument, wordsByPage: Map<Int, List<OcrWord>>) {
        stamped += wordsByPage
    }
}

class FakeOcrRepository : OcrRepository {
    /** Words per page index; a page mapped to an exception throws. */
    val pages = mutableMapOf<Int, Any>()
    override suspend fun recognizePage(source: DocumentInfo, pageIndex: Int): List<OcrWord> {
        val page = pages[pageIndex] ?: return emptyList()
        if (page is Throwable) throw page
        @Suppress("UNCHECKED_CAST") return page as List<OcrWord>
    }
}

class FakeSummaryRepository(var availability: SummaryAvailability = SummaryAvailability.READY) : SummaryRepository {
    var result = "• point"
    var error: Throwable? = null
    val requests = mutableListOf<String>()
    override fun availability() = availability
    override fun grantConsent() { availability = SummaryAvailability.READY }
    override suspend fun summarize(text: String): String {
        requests += text
        error?.let { throw it }
        return result
    }
}

fun word(text: String) = OcrWord(text, 0f, 0f, 0.1f, 0.1f)
