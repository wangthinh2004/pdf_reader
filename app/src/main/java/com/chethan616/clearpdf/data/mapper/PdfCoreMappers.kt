package com.chethan616.clearpdf.data.mapper

import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.kyant.pdfcore.model.CompressionQuality
import com.kyant.pdfcore.model.PdfDocument
import com.kyant.pdfcore.searchable.InvisibleWord
import com.kyant.ocrcore.OcrWord as CoreOcrWord

fun DocumentInfo.toPdfDocument() = PdfDocument(uri = ref.toUri(), name = name, sizeBytes = sizeBytes)

fun CompressionLevel.toQuality(): CompressionQuality = when (this) {
    CompressionLevel.LOW -> CompressionQuality.LOW
    CompressionLevel.MEDIUM -> CompressionQuality.MEDIUM
    CompressionLevel.HIGH -> CompressionQuality.HIGH
}

fun CoreOcrWord.toDomain() = OcrWord(text, left, top, right, bottom)

fun OcrWord.toInvisibleWord() = InvisibleWord(text, left, top, right, bottom)
