package com.chethan616.clearpdf.presentation.feature.tool.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText

data class CompressPdfUiState(
    val source: DocumentInfo? = null,
    val level: CompressionLevel = CompressionLevel.MEDIUM,
    val sliderFraction: Float = 0.5f,
    /** -1 until an estimate is known. */
    val estimatedBytes: Long = -1,
    val isCompressing: Boolean = false,
    /** The last compressed file, for the "Open PDF" button. */
    val output: OutputDocument? = null
) : UiState

sealed interface CompressPdfUiEvent : UiEvent {
    data class ShowStatus(val text: UiText, val isError: Boolean) : CompressPdfUiEvent
}
