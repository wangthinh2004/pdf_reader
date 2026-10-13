package com.chethan616.clearpdf.presentation.feature.tool.compress

import androidx.annotation.StringRes
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.usecase.compress.CompressPdfUseCase
import com.chethan616.clearpdf.domain.usecase.compress.EstimateCompressedSizeUseCase
import com.chethan616.clearpdf.domain.usecase.compress.PrepareCompressionUseCase
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.common.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CompressPdfViewModel @Inject constructor(
    private val openDocument: OpenDocumentUseCase,
    private val prepareCompression: PrepareCompressionUseCase,
    private val estimateSize: EstimateCompressedSizeUseCase,
    private val compressPdf: CompressPdfUseCase
) : BaseViewModel<CompressPdfUiState, CompressPdfUiEvent>(CompressPdfUiState()) {

    fun onFilePicked(ref: DocumentRef) {
        launch(onError = { showError(R.string.open_pdf_failed) }) {
            val source = openDocument(ref)
            val setup = prepareCompression(source)
            setState {
                CompressPdfUiState(
                    source = source,
                    level = setup.level,
                    sliderFraction = setup.sliderFraction,
                    estimatedBytes = setup.estimatedBytes
                )
            }
        }
    }

    fun onSliderChanged(value: Float) {
        val previous = currentState.level
        val level = CompressionLevel.fromFraction(value)
        setState { copy(sliderFraction = value, level = level) }
        val source = currentState.source ?: return
        if (level == previous) return
        // A failed estimate just leaves the previous one on screen, as before.
        launch(onError = {}) {
            val estimate = estimateSize(source, level)
            setState { copy(estimatedBytes = estimate) }
        }
    }

    fun onCompressClicked() {
        val source = currentState.source ?: return
        if (currentState.isCompressing) return
        setState { copy(isCompressing = true, output = null) }
        launch(onError = {
            setState { copy(isCompressing = false) }
            showError(R.string.compression_failed)
        }) {
            val result = compressPdf(source, currentState.level)
            setState { copy(isCompressing = false, output = result.output) }
            sendEvent(
                CompressPdfUiEvent.ShowStatus(
                    UiText.res(
                        R.string.compression_success,
                        result.originalBytes / 1024,
                        result.compressedBytes / 1024,
                        result.reductionPercent,
                        result.output.locationLabel
                    ),
                    isError = false
                )
            )
        }
    }

    private fun showError(@StringRes message: Int) =
        sendEvent(CompressPdfUiEvent.ShowStatus(UiText.res(message), isError = true))
}
