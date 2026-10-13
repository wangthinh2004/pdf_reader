package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import androidx.lifecycle.SavedStateHandle
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OcrWord
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GetSummaryAvailabilityUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GrantSummaryConsentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.SummarizeTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.ExtractTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.MakeSearchablePdfUseCase
import com.chethan616.clearpdf.domain.usecase.text.RecognizeTextUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExtractTextViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val openDocument: OpenDocumentUseCase,
    private val extractText: ExtractTextUseCase,
    private val recognizeText: RecognizeTextUseCase,
    private val makeSearchablePdf: MakeSearchablePdfUseCase,
    private val summarizeText: SummarizeTextUseCase,
    private val getSummaryAvailability: GetSummaryAvailabilityUseCase,
    private val grantSummaryConsent: GrantSummaryConsentUseCase
) : BaseViewModel<ExtractTextUiState, ExtractTextUiEvent>(
    ExtractTextUiState(aiMode = savedStateHandle.get<Boolean>(ARG_AI_MODE) == true)
) {
    /** OCR word boxes, kept so "Save as Searchable PDF" doesn't re-run OCR. */
    private var wordsByPage: Map<Int, List<OcrWord>> = emptyMap()

    /** AI mode asks for a summary at most once per picked file. */
    private var autoSummaryRequested = false

    init {
        setState { copy(summaryAvailable = getSummaryAvailability() != SummaryAvailability.UNAVAILABLE) }
    }

    fun onFilePicked(ref: DocumentRef) {
        wordsByPage = emptyMap()
        autoSummaryRequested = false
        setState { ExtractTextUiState(aiMode = aiMode, summaryAvailable = summaryAvailable, isExtracting = true) }
        launch(onError = {
            setState { copy(isExtracting = false, error = UiText.res(R.string.text_extraction_failed)) }
        }) {
            val source = openDocument(ref)
            val text = extractText(source)
            setState {
                copy(
                    source = source,
                    isExtracting = false,
                    text = text,
                    hasResult = true,
                    canRecognize = text.isEmpty(),
                    error = if (text.isEmpty()) UiText.res(R.string.extract_no_selectable_text) else null
                )
            }
            summarizeIfAiMode()
        }
    }

    fun onRecognizeClicked() {
        val source = currentState.source ?: return
        if (currentState.isRecognizing) return
        setState { copy(isRecognizing = true, recognizeProgress = null) }
        launch(onError = { setState { copy(isRecognizing = false, recognizeProgress = null) } }) {
            val result = recognizeText(source) { page, total -> setState { copy(recognizeProgress = page to total) } }
            wordsByPage = result.wordsByPage
            setState {
                copy(
                    isRecognizing = false,
                    recognizeProgress = null,
                    text = result.text,
                    hasResult = true,
                    canRecognize = false,
                    canMakeSearchable = result.wordsByPage.isNotEmpty(),
                    error = if (result.text.isEmpty()) UiText.res(R.string.extract_no_selectable_text) else null
                )
            }
            summarizeIfAiMode()
        }
    }

    fun onMakeSearchableClicked() {
        val source = currentState.source ?: return
        if (wordsByPage.isEmpty() || currentState.isSavingSearchable) return
        setState { copy(isSavingSearchable = true, error = null, searchableOutput = null) }
        launch(onError = { e ->
            setState {
                copy(isSavingSearchable = false, error = UiText.Raw(e.message ?: "Couldn't create a searchable PDF"))
            }
        }) {
            val output = makeSearchablePdf(source, wordsByPage)
            setState { copy(isSavingSearchable = false, searchableOutput = output) }
        }
    }

    fun onSummarizeClicked() {
        if (currentState.text.isBlank() || currentState.isSummarizing) return
        when (getSummaryAvailability()) {
            SummaryAvailability.UNAVAILABLE -> Unit
            SummaryAvailability.NEEDS_CONSENT -> sendEvent(ExtractTextUiEvent.ShowSummaryConsent)
            SummaryAvailability.READY -> summarize()
        }
    }

    fun onSummaryConsentGranted() {
        grantSummaryConsent()
        summarize()
    }

    private fun summarizeIfAiMode() {
        if (!currentState.aiMode || autoSummaryRequested || currentState.text.isEmpty()) return
        autoSummaryRequested = true
        onSummarizeClicked()
    }

    private fun summarize() {
        val text = currentState.text
        if (text.isBlank() || currentState.isSummarizing) return
        setState { copy(isSummarizing = true, summary = null, summaryError = null) }
        launch(onError = { e ->
            val message = if (e is AppError.QuotaExceeded) {
                UiText.res(R.string.ai_summary_quota)
            } else {
                UiText.res(R.string.ai_summary_failed, e.message.orEmpty())
            }
            setState { copy(isSummarizing = false, summaryError = message) }
        }) {
            val summary = summarizeText(text)
            setState { copy(isSummarizing = false, summary = summary) }
        }
    }
}
