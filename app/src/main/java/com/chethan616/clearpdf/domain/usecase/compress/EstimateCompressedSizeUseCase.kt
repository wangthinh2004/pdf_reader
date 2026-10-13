package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.repository.PdfRepository
import javax.inject.Inject

class EstimateCompressedSizeUseCase @Inject constructor(private val pdf: PdfRepository) {
    suspend operator fun invoke(source: DocumentInfo, level: CompressionLevel): Long =
        pdf.estimateCompressedSize(source, level)
}
