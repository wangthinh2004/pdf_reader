package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.repository.PdfRepository
import javax.inject.Inject

/** The PDF's own text layer; empty for scans. */
class ExtractTextUseCase @Inject constructor(private val pdf: PdfRepository) {
    suspend operator fun invoke(source: DocumentInfo): String = pdf.extractText(source).trim()
}
