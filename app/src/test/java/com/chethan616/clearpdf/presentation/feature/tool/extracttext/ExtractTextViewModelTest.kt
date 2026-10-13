package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.SummaryAvailability
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GetSummaryAvailabilityUseCase
import com.chethan616.clearpdf.domain.usecase.summary.GrantSummaryConsentUseCase
import com.chethan616.clearpdf.domain.usecase.summary.SummarizeTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.ExtractTextUseCase
import com.chethan616.clearpdf.domain.usecase.text.MakeSearchablePdfUseCase
import com.chethan616.clearpdf.domain.usecase.text.RecognizeTextUseCase
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeOcrRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.FakeSummaryRepository
import com.chethan616.clearpdf.testing.MainDispatcherRule
import com.chethan616.clearpdf.testing.SAMPLE_REF
import com.chethan616.clearpdf.testing.word
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ExtractTextViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository()
    private val ocr = FakeOcrRepository()
    private val summaries = FakeSummaryRepository()

    private fun viewModel(aiMode: Boolean = false) = ExtractTextViewModel(
        SavedStateHandle(mapOf(ARG_AI_MODE to aiMode)),
        OpenDocumentUseCase(documents),
        ExtractTextUseCase(pdf),
        RecognizeTextUseCase(pdf, ocr),
        MakeSearchablePdfUseCase(documents, pdf, FakeRecentFilesRepository()),
        SummarizeTextUseCase(summaries),
        GetSummaryAvailabilityUseCase(summaries),
        GrantSummaryConsentUseCase(summaries)
    )

    @Test fun textLayer_isShown() {
        pdf.text = "Hello"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        assertEquals("Hello", vm.state.value.text)
        assertFalse(vm.state.value.canRecognize)
        assertNull(vm.state.value.error)
    }

    @Test fun scannedPdf_offersOcr() {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        assertTrue(vm.state.value.canRecognize)
        assertEquals(UiText.res(R.string.extract_no_selectable_text), vm.state.value.error)
    }

    @Test fun ocr_replacesTextAndEnablesSearchable() {
        pdf.pages = 1
        ocr.pages[0] = listOf(word("Scanned"))
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onRecognizeClicked()
        val state = vm.state.value
        assertEquals("Scanned", state.text)
        assertFalse(state.canRecognize)
        assertTrue(state.canMakeSearchable)
        assertNull(state.recognizeProgress)
    }

    @Test fun searchable_savesOutput() {
        pdf.pages = 1
        ocr.pages[0] = listOf(word("Scanned"))
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onRecognizeClicked()
        vm.onMakeSearchableClicked()
        assertEquals("ClearPDF_Searchable.pdf", vm.state.value.searchableOutput?.name)
    }

    @Test fun aiMode_summarizesAutomaticallyOnce() {
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        assertEquals("• point", vm.state.value.summary?.text)
        assertEquals(listOf("Body"), summaries.requests)
    }

    @Test fun aiMode_onScan_summarizesAfterOcr() {
        pdf.pages = 1
        ocr.pages[0] = listOf(word("Scanned"))
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        assertTrue(summaries.requests.isEmpty())
        vm.onRecognizeClicked()
        assertEquals(listOf("Scanned"), summaries.requests)
    }

    @Test fun aiMode_needsConsent_asksOnceAndNotAgainAfterDecline() = runTest {
        summaries.availability = SummaryAvailability.NEEDS_CONSENT
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.events.test {
            vm.onFilePicked(SAMPLE_REF)
            assertEquals(ExtractTextUiEvent.ShowSummaryConsent, awaitItem())
            // User declined (no onSummaryConsentGranted). State changes must not re-ask.
            vm.onRecognizeClicked()
            expectNoEvents()
        }
        assertTrue(summaries.requests.isEmpty())
    }

    @Test fun consentGranted_summarizes() {
        summaries.availability = SummaryAvailability.NEEDS_CONSENT
        pdf.text = "Body"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSummaryConsentGranted()
        assertEquals("• point", vm.state.value.summary?.text)
        assertEquals(SummaryAvailability.READY, summaries.availability)
    }

    @Test fun quotaExceeded_showsQuotaMessageAndAllowsRetry() {
        summaries.error = AppError.QuotaExceeded()
        pdf.text = "Body"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSummarizeClicked()
        assertEquals(UiText.res(R.string.ai_summary_quota), vm.state.value.summaryError)
        assertFalse(vm.state.value.isSummarizing)
    }

    @Test fun networkFailure_showsReason() {
        summaries.error = AppError.Network(IOException("Unable to resolve host"))
        pdf.text = "Body"
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onSummarizeClicked()
        assertEquals(UiText.res(R.string.ai_summary_failed, "Unable to resolve host"), vm.state.value.summaryError)
    }

    @Test fun summaryHiddenWhenUnavailable() {
        summaries.availability = SummaryAvailability.UNAVAILABLE
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        assertFalse(vm.state.value.summaryAvailable)
        assertTrue(summaries.requests.isEmpty())
    }

    @Test fun pickingANewFile_clearsPreviousSummary() {
        pdf.text = "Body"
        val vm = viewModel(aiMode = true)
        vm.onFilePicked(SAMPLE_REF)
        pdf.text = ""
        vm.onFilePicked(SAMPLE_REF)
        assertNull(vm.state.value.summary)
    }
}
