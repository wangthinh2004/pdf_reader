package com.chethan616.clearpdf.domain.model

/** What a PDF → Word conversion produced. */
data class WordConversion(
    val pages: Int,
    /** Pages that had no text layer and were read with OCR. */
    val recognizedPages: Int,
    val tables: Int,
    val pictures: Int,
    val sizeBytes: Long
)
