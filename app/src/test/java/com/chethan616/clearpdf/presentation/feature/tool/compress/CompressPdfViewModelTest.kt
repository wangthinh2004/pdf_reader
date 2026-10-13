package com.chethan616.clearpdf.presentation.feature.tool.compress

import app.cash.turbine.test
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.usecase.compress.CompressPdfUseCase
import com.chethan616.clearpdf.domain.usecase.compress.EstimateCompressedSizeUseCase
import com.chethan616.clearpdf.domain.usecase.compress.PrepareCompressionUseCase
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeEngagementRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.FakeSettingsRepository
import com.chethan616.clearpdf.testing.MainDispatcherRule
import com.chethan616.clearpdf.testing.SAMPLE_REF
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class CompressPdfViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository()

    private fun viewModel() = CompressPdfViewModel(
        OpenDocumentUseCase(documents),
        PrepareCompressionUseCase(FakeSettingsRepository(fraction = 0.5f), pdf),
        EstimateCompressedSizeUseCase(pdf),
        CompressPdfUseCase(documents, pdf, FakeRecentFilesRepository(), FakeEngagementRepository())
    )

    @Test fun pickingAFile_showsItWithDefaultQualityAndEstimate() {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        val state = vm.state.value
        assertEquals("sample.pdf", state.source?.name)
        assertEquals(CompressionLevel.MEDIUM, state.level)
        assertEquals(0.5f, state.sliderFraction)
        assertEquals(100 * 1024L, state.estimatedBytes)
    }

    @Test fun movingSliderAcrossALevel_reEstimates() {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSliderChanged(0.9f)
        assertEquals(CompressionLevel.HIGH, vm.state.value.level)
        assertEquals(150 * 1024L, vm.state.value.estimatedBytes)
    }

    @Test fun compressSuccess_showsOutputAndOneSuccessMessage() = runTest {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onCompressClicked()
        assertFalse(vm.state.value.isCompressing)
        assertEquals("ClearPDF_Compressed.pdf", vm.state.value.output?.name)
        vm.events.test {
            assertEquals(
                CompressPdfUiEvent.ShowStatus(UiText.res(R.string.compression_success, 200L, 100L, 50, "Downloads (default)"), isError = false),
                awaitItem()
            )
            expectNoEvents()
        }
    }

    @Test fun compressFailure_showsErrorAndRestoresButton() = runTest {
        pdf.compressError = IOException("broken")
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onCompressClicked()
        assertFalse(vm.state.value.isCompressing)
        assertNull(vm.state.value.output)
        vm.events.test {
            assertEquals(CompressPdfUiEvent.ShowStatus(UiText.res(R.string.compression_failed), isError = true), awaitItem())
        }
    }

    @Test fun unreadableFile_showsOpenError() = runTest {
        documents.openError = IOException("gone")
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        assertNull(vm.state.value.source)
        vm.events.test {
            assertEquals(CompressPdfUiEvent.ShowStatus(UiText.res(R.string.open_pdf_failed), isError = true), awaitItem())
        }
    }
}
