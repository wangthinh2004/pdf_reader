package com.chethan616.clearpdf.presentation.feature.tool.pdftoword

import app.cash.turbine.test
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.usecase.convert.ConvertPdfToWordUseCase
import com.chethan616.clearpdf.domain.usecase.document.OpenDocumentUseCase
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeEngagementRepository
import com.chethan616.clearpdf.testing.FakeOcrRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
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

class PdfToWordViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository().apply { pages = 3 }
    private val ocr = FakeOcrRepository()
    private val recents = FakeRecentFilesRepository()

    private fun viewModel() = PdfToWordViewModel(
        OpenDocumentUseCase(documents),
        ConvertPdfToWordUseCase(documents, pdf, ocr, recents, FakeEngagementRepository())
    )

    @Test fun convertSuccess_savesDocxNamedAfterThePdfAndListsItInRecents() = runTest {
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.events.test {
            vm.onConvertClicked()
            val event = awaitItem() as PdfToWordUiEvent.ShowStatus
            assertFalse(event.isError)
            assertEquals(R.string.pdf_to_word_success, (event.text as UiText.Res).id)
        }
        val state = vm.state.value
        assertFalse(state.isConverting)
        assertNull(state.progress)
        assertEquals("sample.docx", state.output?.name)
        assertEquals(listOf("sample"), documents.createdPrefixes)
        assertEquals(4096L, recents.added.single().second)
    }

    @Test fun scannedPages_goThroughOcr_andTheMessageSaysSo() = runTest {
        pdf.scannedPages = setOf(1)
        ocr.pages[1] = listOf(word("Hello"))
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.events.test {
            vm.onConvertClicked()
            val event = awaitItem() as PdfToWordUiEvent.ShowStatus
            assertEquals(R.string.pdf_to_word_success_ocr, (event.text as UiText.Res).id)
        }
        assertEquals(mapOf(1 to listOf(word("Hello"))), pdf.recognized)
    }

    @Test fun ocrFailureOnAPage_doesNotFailTheConversion() = runTest {
        pdf.scannedPages = setOf(0)
        ocr.pages[0] = IllegalStateException("model missing")
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.onConvertClicked()
        assertTrue(vm.state.value.output != null)
    }

    @Test fun lockedPdf_showsThePasswordMessage() = runTest {
        pdf.convertError = AppError.PasswordProtected()
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.events.test {
            vm.onConvertClicked()
            val event = awaitItem() as PdfToWordUiEvent.ShowStatus
            assertTrue(event.isError)
            assertEquals(R.string.pdf_to_word_locked, (event.text as UiText.Res).id)
        }
        assertFalse(vm.state.value.isConverting)
        assertNull(vm.state.value.output)
    }

    @Test fun otherFailures_showTheGenericMessage() = runTest {
        pdf.convertError = IllegalStateException("broken xref")
        val vm = viewModel()
        vm.onFilePicked(SAMPLE_REF)
        vm.events.test {
            vm.onConvertClicked()
            assertEquals(R.string.pdf_to_word_failed, ((awaitItem() as PdfToWordUiEvent.ShowStatus).text as UiText.Res).id)
        }
    }
}
