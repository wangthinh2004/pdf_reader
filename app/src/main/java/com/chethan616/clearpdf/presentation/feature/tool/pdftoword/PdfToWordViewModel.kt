package com.chethan616.clearpdf.presentation.feature.tool.pdftoword

import androidx.annotation.StringRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.usecase.convert.ConvertPdfToWordUseCase
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PdfToWordViewModel @Inject constructor(
    private val openDocument: OpenDocumentUseCase,
    private val convertPdfToWord: ConvertPdfToWordUseCase
) : BaseViewModel<PdfToWordUiState, PdfToWordUiEvent>(PdfToWordUiState()) {

    fun onFilePicked(ref: DocumentRef) {
        if (currentState.isConverting) return
        launch(onError = { showError(R.string.open_pdf_failed) }) {
            val source = openDocument(ref)
            setState { PdfToWordUiState(source = source) }
        }
    }

    fun onConvertClicked() {
        val source = currentState.source ?: return
        if (currentState.isConverting) return
        setState { copy(isConverting = true, progress = null, output = null) }
        launch(onError = { error ->
            setState { copy(isConverting = false, progress = null) }
            showError(if (error is AppError.PasswordProtected) R.string.pdf_to_word_locked else R.string.pdf_to_word_failed)
        }) {
            val result = convertPdfToWord(source) { page, total -> setState { copy(progress = page to total) } }
            setState { copy(isConverting = false, progress = null, output = result.output) }
            val c = result.conversion
            val message = if (c.recognizedPages > 0) {
                UiText.res(R.string.pdf_to_word_success_ocr, result.output.locationLabel, c.pages, c.recognizedPages)
            } else {
                UiText.res(R.string.pdf_to_word_success, result.output.locationLabel, c.pages)
            }
            sendEvent(PdfToWordUiEvent.ShowStatus(message, isError = false))
        }
    }

    private fun showError(@StringRes message: Int) =
        sendEvent(PdfToWordUiEvent.ShowStatus(UiText.res(message), isError = true))
}
