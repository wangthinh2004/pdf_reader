package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import com.chethan616.clearpdf.domain.repository.EngagementRepository
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import javax.inject.Inject

data class CompressionResult(val output: OutputDocument, val originalBytes: Long, val compressedBytes: Long) {
    /** Size saved, in whole percent; 0 when the original size is unknown. */
    val reductionPercent: Int
        get() = if (originalBytes > 0) (100 - compressedBytes * 100 / originalBytes).toInt() else 0
}

/** Writes a compressed copy next to the user's other outputs and lists it in Recents. */
class CompressPdfUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val pdf: PdfRepository,
    private val recentFiles: RecentFilesRepository,
    private val engagement: EngagementRepository
) {
    suspend operator fun invoke(source: DocumentInfo, level: CompressionLevel): CompressionResult {
        val output = documents.createOutput(prefix = "ClearPDF_Compressed", extension = "pdf", mimeType = "application/pdf")
        val compressedBytes = pdf.compress(source, level, output)
        recentFiles.add(output, compressedBytes)
        engagement.recordPdfInteraction()
        return CompressionResult(output, source.sizeBytes, compressedBytes)
    }
}
