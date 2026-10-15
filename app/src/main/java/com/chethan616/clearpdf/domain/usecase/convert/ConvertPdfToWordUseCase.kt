package com.chethan616.clearpdf.domain.usecase.convert

import com.chethan616.clearpdf.core.result.runCatchingCancellable
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.model.WordConversion
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import javax.inject.Inject

data class WordConversionResult(val output: OutputDocument, val conversion: WordConversion)

/**
 * PDF → .docx next to the user's other outputs, listed in Recents. Scanned pages are read with
 * on-device OCR; a page OCR can't read keeps its picture instead of failing the conversion.
 */
class ConvertPdfToWordUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val pdf: PdfRepository,
    private val ocr: OcrRepository,
    private val recentFiles: RecentFilesRepository,
    private val engagement: EngagementRepository
) {
    suspend operator fun invoke(source: DocumentInfo, onProgress: (page: Int, total: Int) -> Unit): WordConversionResult {
        val output = documents.createOutput(
            prefix = source.name.substringBeforeLast('.').ifBlank { "ClearPDF_Word" },
            extension = "docx",
            mimeType = MIME_DOCX
        )
        val conversion = pdf.convertToWord(
            source,
            output,
            recognize = { page -> runCatchingCancellable { ocr.recognizePage(source, page) }.getOrDefault(emptyList()) },
            onProgress = onProgress
        )
        recentFiles.add(output, conversion.sizeBytes)
        engagement.recordPdfInteraction()
        return WordConversionResult(output, conversion)
    }

    companion object {
        const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
