package com.chethan616.clearpdf.presentation.feature.tool.pdftoword

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText

data class PdfToWordUiState(
    val source: DocumentInfo? = null,
    val isConverting: Boolean = false,
    /** (page being read, page count) while converting; page == count while the .docx is written. */
    val progress: Pair<Int, Int>? = null,
    /** The last converted document, for the "Open document" button. */
    val output: OutputDocument? = null
) : UiState

sealed interface PdfToWordUiEvent : UiEvent {
    data class ShowStatus(val text: UiText, val isError: Boolean) : PdfToWordUiEvent
}
