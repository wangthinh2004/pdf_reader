package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import javax.inject.Inject

/** Copies the PDF with the OCR words as an invisible, selectable text layer. */
class MakeSearchablePdfUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val pdf: PdfRepository,
    private val recentFiles: RecentFilesRepository
) {
    suspend operator fun invoke(source: DocumentInfo, wordsByPage: Map<Int, List<OcrWord>>): OutputDocument {
        val output = documents.createOutput(prefix = "ClearPDF_Searchable", extension = "pdf", mimeType = "application/pdf")
        pdf.stampSearchableText(source, output, wordsByPage)
        recentFiles.add(output, sizeBytes = 0)
        return output
    }
}
