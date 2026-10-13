package com.chethan616.clearpdf.domain.model

/** A recognized word with its box normalized to the page (0..1). */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
