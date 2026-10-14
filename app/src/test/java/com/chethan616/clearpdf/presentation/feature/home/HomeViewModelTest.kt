package com.chethan616.clearpdf.presentation.feature.home

import androidx.lifecycle.SavedStateHandle
import com.chethan616.clearpdf.domain.model.DocumentSort
import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.domain.usecase.library.DeleteDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.library.FilterDocumentsUseCase
import com.chethan616.clearpdf.domain.usecase.library.LoadLibraryUseCase
import com.chethan616.clearpdf.domain.usecase.library.ObserveFavoritesUseCase
import com.chethan616.clearpdf.domain.usecase.library.ToggleFavoriteUseCase
import com.chethan616.clearpdf.testing.FakeFavoritesRepository
import com.chethan616.clearpdf.testing.FakeLibraryRepository
import com.chethan616.clearpdf.testing.FakeRecentFilesRepository
import com.chethan616.clearpdf.testing.MainDispatcherRule
import com.chethan616.clearpdf.testing.libraryDoc
import com.chethan616.clearpdf.utils.DocKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val report = libraryDoc("Report.pdf", modifiedAt = 300)
    private val budget = libraryDoc("Budget.xlsx", modifiedAt = 100)
    private val library = FakeLibraryRepository(documents = listOf(budget, report))
    private val favorites = FakeFavoritesRepository()

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle()) = HomeViewModel(
        saved,
        LoadLibraryUseCase(library, FakeRecentFilesRepository()),
        FilterDocumentsUseCase(),
        ObserveFavoritesUseCase(favorites),
        ToggleFavoriteUseCase(favorites),
        DeleteDocumentUseCase(library, favorites, FakeRecentFilesRepository())
    ).also { it.refresh() }

    private val HomeViewModel.docs get() = state.value.items.map { it.document }

    @Test
    fun `lists device documents newest first and filters by tab`() {
        val vm = viewModel()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf(report, budget), vm.docs)
        vm.onKindSelected(DocKind.Excel)
        assertEquals(listOf(budget), vm.docs)
    }

    @Test
    fun `tab, sort and view mode survive process death`() {
        val saved = SavedStateHandle()
        viewModel(saved).apply {
            onKindSelected(DocKind.Pdf)
            onSortSelected(DocumentSort.NAME)
            onViewModeSelected(grid = true)
        }
        val restored = viewModel(saved).state.value
        assertEquals(DocKind.Pdf, restored.kind)
        assertEquals(DocumentSort.NAME, restored.sort)
        assertTrue(restored.grid)
    }

    @Test
    fun `starring updates the row`() = runTest {
        val vm = viewModel()
        vm.onStarClicked(report)
        assertTrue(vm.state.value.items.first { it.document == report }.favorite)
    }

    @Test
    fun `empty states`() {
        library.documents = emptyList()
        val vm = viewModel()
        assertEquals(HomeEmpty.NO_DOCUMENTS, vm.state.value.empty)

        library.documents = listOf(report)
        vm.refresh()
        vm.onQueryChanged("zzz")
        assertEquals(HomeEmpty.NO_MATCHES, vm.state.value.empty)
        vm.onQueryChanged("")
        assertNull(vm.state.value.empty)

        library.access = LibraryAccess.NEEDS_PERMISSION
        vm.refresh()
        assertEquals(HomeEmpty.NEEDS_ACCESS, vm.state.value.empty)
    }

    @Test
    fun `long press starts selection, click toggles, back leaves it`() = runTest {
        val vm = viewModel()
        vm.onDocumentLongClicked(report)
        assertTrue(vm.state.value.selectionMode)
        assertEquals(setOf(report.ref), vm.state.value.selected)

        vm.onDocumentClicked(budget)
        assertEquals(setOf(report.ref, budget.ref), vm.state.value.selected)

        assertTrue(vm.onBack())
        assertFalse(vm.state.value.selectionMode)
        assertFalse(vm.onBack())

        vm.onDocumentClicked(report)
        assertEquals(HomeUiEvent.Open(report), vm.events.first())
    }

    @Test
    fun `deleting the selection removes the files and leaves selection mode`() = runTest {
        val vm = viewModel()
        vm.onDocumentLongClicked(report)
        vm.onDeleteSelected()
        assertEquals(HomeUiEvent.ConfirmDelete(1), vm.events.first())

        library.documents = listOf(budget)
        vm.onDeleteConfirmed()
        assertFalse(vm.state.value.selectionMode)
        assertEquals(listOf(budget), vm.docs)
    }
}
