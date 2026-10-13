package com.chethan616.clearpdf.domain.usecase.summary

import com.chethan616.clearpdf.testing.FakeSummaryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizeTextUseCaseTest {
    private val repo = FakeSummaryRepository()

    @Test fun shortText_isSentWhole() = runTest {
        val summary = SummarizeTextUseCase(repo)("short doc")
        assertEquals(listOf("short doc"), repo.requests)
        assertEquals("• point", summary.text)
        assertFalse(summary.truncated)
    }

    @Test fun longText_isCutToTheLimitAndFlagged() = runTest {
        val text = "a".repeat(SummarizeTextUseCase.MAX_INPUT_CHARS + 5)
        val summary = SummarizeTextUseCase(repo)(text)
        assertEquals(SummarizeTextUseCase.MAX_INPUT_CHARS, repo.requests.single().length)
        assertTrue(summary.truncated)
    }

    @Test fun textExactlyAtTheLimit_isNotFlagged() = runTest {
        val summary = SummarizeTextUseCase(repo)("a".repeat(SummarizeTextUseCase.MAX_INPUT_CHARS))
        assertFalse(summary.truncated)
    }
}
