package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.core.result.runCatchingCancellable
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.repository.OcrRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import javax.inject.Inject

data class RecognizedText(val text: String, val wordsByPage: Map<Int, List<OcrWord>>)

/**
 * On-device OCR for scanned PDFs, page by page. A page that can't be rendered or recognized is
 * skipped rather than failing the whole document.
 */
class RecognizeTextUseCase @Inject constructor(
    private val pdf: PdfRepository,
    private val ocr: OcrRepository
) {
    suspend operator fun invoke(source: DocumentInfo, onProgress: (page: Int, total: Int) -> Unit): RecognizedText {
        val total = runCatchingCancellable { pdf.pageCount(source) }.getOrDefault(0)
        val wordsByPage = linkedMapOf<Int, List<OcrWord>>()
        for (page in 0 until total) {
            onProgress(page, total)
            val words = runCatchingCancellable { ocr.recognizePage(source, page) }.getOrDefault(emptyList())
            if (words.isNotEmpty()) wordsByPage[page] = words
        }
        val text = wordsByPage.values.joinToString("\n\n") { words -> words.joinToString(" ") { it.text } }
        return RecognizedText(text.trim(), wordsByPage)
    }
}
