package com.chethan616.clearpdf.domain.usecase.text

import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeOcrRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.SAMPLE_DOC
import com.chethan616.clearpdf.testing.word
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TextUseCasesTest {
    private val pdf = FakePdfRepository()
    private val ocr = FakeOcrRepository()

    @Test fun extract_trimsText() = runTest {
        pdf.text = "\n  Hello world \n"
        assertEquals("Hello world", ExtractTextUseCase(pdf)(SAMPLE_DOC))
    }

    @Test fun recognize_joinsPagesAndReportsProgress() = runTest {
        pdf.pages = 3
        ocr.pages[0] = listOf(word("Page"), word("one"))
        ocr.pages[2] = listOf(word("three"))
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = RecognizeTextUseCase(pdf, ocr)(SAMPLE_DOC) { page, total -> progress += page to total }
        assertEquals("Page one\n\nthree", result.text)
        assertEquals(setOf(0, 2), result.wordsByPage.keys)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3), progress)
    }

    @Test fun recognize_skipsPagesThatFail() = runTest {
        pdf.pages = 2
        ocr.pages[0] = IOException("render failed")
        ocr.pages[1] = listOf(word("ok"))
        val result = RecognizeTextUseCase(pdf, ocr)(SAMPLE_DOC) { _, _ -> }
        assertEquals("ok", result.text)
    }

    @Test fun recognize_unreadablePdfGivesEmptyResult() = runTest {
        pdf.pageCountError = IOException("not a pdf")
        val result = RecognizeTextUseCase(pdf, ocr)(SAMPLE_DOC) { _, _ -> }
        assertEquals("", result.text)
        assertTrue(result.wordsByPage.isEmpty())
    }

    @Test fun searchable_stampsWordsIntoNewOutputAndListsIt() = runTest {
        val documents = FakeDocumentRepository()
        val recents = FakeRecentFilesRepository()
        val words = mapOf(0 to listOf(word("hi")))
        val output = MakeSearchablePdfUseCase(documents, pdf, recents)(SAMPLE_DOC, words)
        assertEquals(listOf("ClearPDF_Searchable"), documents.createdPrefixes)
        assertEquals(listOf(words), pdf.stamped)
        assertEquals(output to 0L, recents.added.single())
    }
}
