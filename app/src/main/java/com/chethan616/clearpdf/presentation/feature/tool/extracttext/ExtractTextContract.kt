package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.model.Summary
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText

/** Navigation argument set by the AI Summary tool (see nav_graph.xml). */
const val ARG_AI_MODE = "ai_mode"

data class ExtractTextUiState(
    /** Opened from the AI Summary tool: summarize as soon as text is ready. */
    val aiMode: Boolean = false,
    val source: DocumentInfo? = null,
    val isExtracting: Boolean = false,
    val text: String = "",
    val hasResult: Boolean = false,
    /** Inline warning under the file name. */
    val error: UiText? = null,
    /** No text layer was found: offer on-device OCR. */
    val canRecognize: Boolean = false,
    val isRecognizing: Boolean = false,
    /** (zero-based page, total) while OCR runs. */
    val recognizeProgress: Pair<Int, Int>? = null,
    val canMakeSearchable: Boolean = false,
    val isSavingSearchable: Boolean = false,
    val searchableOutput: OutputDocument? = null,
    /** The build can reach Gemini (key + INTERNET). */
    val summaryAvailable: Boolean = false,
    val isSummarizing: Boolean = false,
    val summary: Summary? = null,
    val summaryError: UiText? = null
) : UiState

sealed interface ExtractTextUiEvent : UiEvent {
    /** Ask before the first upload of document text to Gemini. */
    data object ShowSummaryConsent : ExtractTextUiEvent
}
