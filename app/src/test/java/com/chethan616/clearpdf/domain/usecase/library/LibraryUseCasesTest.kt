package com.chethan616.clearpdf.domain.usecase.library

import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentSort
import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.testing.FakeFavoritesRepository
import com.chethan616.clearpdf.testing.FakeLibraryRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.libraryDoc
import com.chethan616.clearpdf.utils.DocKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryUseCasesTest {

    private val report = libraryDoc("Report.pdf", modifiedAt = 300, sizeBytes = 10)
    private val budget = libraryDoc("budget.xlsx", modifiedAt = 100, sizeBytes = 900)
    private val notes = libraryDoc("Meeting notes.docx", modifiedAt = 200, sizeBytes = 50)
    private val all = listOf(budget, notes, report)

    @Test
    fun `filter by kind and query, sorted newest first`() {
        val filter = FilterDocumentsUseCase()
        assertEquals(listOf(report, notes, budget), filter(all, null, "", DocumentSort.DATE_MODIFIED))
        assertEquals(listOf(budget), filter(all, DocKind.Excel, "", DocumentSort.DATE_MODIFIED))
        assertEquals(listOf(notes), filter(all, null, "  meeting ", DocumentSort.DATE_MODIFIED))
    }

    @Test
    fun `sort by name ignores case, by size largest first`() {
        val filter = FilterDocumentsUseCase()
        assertEquals(listOf(budget, notes, report), filter(all, null, "", DocumentSort.NAME))
        assertEquals(listOf(budget, notes, report), filter(all, null, "", DocumentSort.SIZE))
    }

    @Test
    fun `library lists device documents, recents on Android 10, nothing without permission`() = runTest {
        val library = FakeLibraryRepository(documents = all)
        val recents = FakeRecentFilesRepository(stored = listOf(report))
        val load = LoadLibraryUseCase(library, recents)

        assertEquals(all, load().documents)
        library.access = LibraryAccess.UNSUPPORTED
        assertEquals(listOf(report), load().documents)
        library.access = LibraryAccess.NEEDS_PERMISSION
        assertEquals(LibraryAccess.NEEDS_PERMISSION, load().access)
        assertTrue(load().documents.isEmpty())
    }

    @Test
    fun `toggle favorite stars then unstars`() = runTest {
        val favorites = FakeFavoritesRepository()
        val toggle = ToggleFavoriteUseCase(favorites)
        assertTrue(toggle(report))
        assertEquals(listOf(report), favorites.favorites.value)
        assertFalse(toggle(report))
        assertTrue(favorites.favorites.value.isEmpty())
    }

    @Test
    fun `rename keeps the extension and moves the favorite`() = runTest {
        val library = FakeLibraryRepository()
        val favorites = FakeFavoritesRepository(listOf(report))
        val recents = FakeRecentFilesRepository()
        val renamed = RenameDocumentUseCase(library, favorites, recents)(report, " Q3 report ")

        assertEquals("Q3 report.pdf", renamed.name)
        assertEquals(listOf(renamed), favorites.favorites.value)
        assertEquals(listOf(report.ref), recents.removed)
    }

    @Test
    fun `rename rejects empty and invalid names`() = runTest {
        val rename = RenameDocumentUseCase(FakeLibraryRepository(), FakeFavoritesRepository(), FakeRecentFilesRepository())
        for (bad in listOf("  ", "a/b", "what?")) {
            try {
                rename(report, bad)
                fail("Expected InvalidName for '$bad'")
            } catch (_: AppError.InvalidName) {
            }
        }
    }

    @Test
    fun `delete forgets the file only when storage deleted it`() = runTest {
        val library = FakeLibraryRepository()
        val favorites = FakeFavoritesRepository(listOf(report))
        val recents = FakeRecentFilesRepository()
        val delete = DeleteDocumentUseCase(library, favorites, recents)

        library.deleteSucceeds = false
        assertFalse(delete(report))
        assertEquals(listOf(report), favorites.favorites.value)

        library.deleteSucceeds = true
        assertTrue(delete(report))
        assertTrue(favorites.favorites.value.isEmpty())
        assertEquals(listOf(report.ref), recents.removed)
    }
}
