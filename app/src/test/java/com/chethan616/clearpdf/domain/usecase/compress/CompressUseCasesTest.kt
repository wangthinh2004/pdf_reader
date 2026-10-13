package com.chethan616.clearpdf.domain.usecase.compress

import com.chethan616.clearpdf.domain.model.CompressionLevel
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.testing.FakeDocumentRepository
import com.chethan616.clearpdf.testing.FakeEngagementRepository
import com.chethan616.clearpdf.testing.FakePdfRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.FakeSettingsRepository
import com.chethan616.clearpdf.testing.SAMPLE_DOC
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CompressUseCasesTest {
    private val documents = FakeDocumentRepository()
    private val pdf = FakePdfRepository()
    private val recents = FakeRecentFilesRepository()
    private val engagement = FakeEngagementRepository()

    @Test fun prepare_startsFromDefaultQualitySetting() = runTest {
        val setup = PrepareCompressionUseCase(FakeSettingsRepository(fraction = 0.2f), pdf)(SAMPLE_DOC)
        assertEquals(CompressionLevel.LOW, setup.level)
        assertEquals(0.2f, setup.sliderFraction)
        assertEquals(50 * 1024L, setup.estimatedBytes)
    }

    @Test fun compress_writesTimestampedOutputAndRecordsIt() = runTest {
        val result = CompressPdfUseCase(documents, pdf, recents, engagement)(SAMPLE_DOC, CompressionLevel.MEDIUM)
        assertEquals(listOf("ClearPDF_Compressed"), documents.createdPrefixes)
        assertEquals(result.output to 100 * 1024L, recents.added.single())
        assertEquals(1, engagement.interactions)
        assertEquals(200 * 1024L, result.originalBytes)
        assertEquals(100 * 1024L, result.compressedBytes)
        assertEquals(50, result.reductionPercent)
    }

    @Test fun reductionPercent_isZeroWhenOriginalSizeUnknown() {
        val result = CompressionResult(output = OUTPUT, originalBytes = -1, compressedBytes = 10)
        assertEquals(0, result.reductionPercent)
    }

    @Test fun compress_failureRecordsNothing() = runTest {
        pdf.compressError = IOException("broken pdf")
        val failure = runCatching { CompressPdfUseCase(documents, pdf, recents, engagement)(SAMPLE_DOC, CompressionLevel.HIGH) }
        assertTrue(failure.isFailure)
        assertTrue(recents.added.isEmpty())
        assertEquals(0, engagement.interactions)
    }

    private companion object {
        val OUTPUT = OutputDocument(DocumentRef("content://out/x.pdf"), "x.pdf", "Downloads (default)")
    }
}
