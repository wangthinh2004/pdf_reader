package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.repository.PdfRepository
import com.chethan616.clearpdf.domain.repository.SettingsRepository
import javax.inject.Inject

data class CompressionSetup(val level: CompressionLevel, val sliderFraction: Float, val estimatedBytes: Long)

/** Starting point for a newly picked file: the user's Default Quality setting and its estimate. */
class PrepareCompressionUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val pdf: PdfRepository
) {
    suspend operator fun invoke(source: DocumentInfo): CompressionSetup {
        val fraction = settings.defaultCompressionFraction()
        val level = CompressionLevel.fromFraction(fraction)
        return CompressionSetup(level, fraction, pdf.estimateCompressedSize(source, level))
    }
}
