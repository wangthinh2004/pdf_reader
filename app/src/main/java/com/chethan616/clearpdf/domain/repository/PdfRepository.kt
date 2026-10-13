package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.OutputDocument

/** PDF operations backed by :pdf-core. */
interface PdfRepository {
    suspend fun estimateCompressedSize(source: DocumentInfo, level: CompressionLevel): Long

    /** Writes the compressed copy to [output]; returns the size of the written file in bytes. */
    suspend fun compress(source: DocumentInfo, level: CompressionLevel, output: OutputDocument): Long

    suspend fun extractText(source: DocumentInfo): String

    /** 0 when the file can't be opened as a PDF. */
    suspend fun pageCount(source: DocumentInfo): Int

    suspend fun stampSearchableText(source: DocumentInfo, output: OutputDocument, wordsByPage: Map<Int, List<OcrWord>>)
}
