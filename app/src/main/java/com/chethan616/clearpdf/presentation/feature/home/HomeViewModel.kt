package com.chethan616.clearpdf.presentation.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.DocumentSort
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.usecase.library.DeleteDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.library.FilterDocumentsUseCase
import com.chethan616.clearpdf.domain.usecase.library.LoadLibraryUseCase
import com.chethan616.clearpdf.domain.usecase.library.ObserveFavoritesUseCase
import com.chethan616.clearpdf.domain.usecase.library.ToggleFavoriteUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.utils.DocKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val loadLibrary: LoadLibraryUseCase,
    private val filterDocuments: FilterDocumentsUseCase,
    observeFavorites: ObserveFavoritesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val deleteDocument: DeleteDocumentUseCase
) : BaseViewModel<HomeUiState, HomeUiEvent>(
    HomeUiState(
        kind = savedState.get<String>(KEY_KIND)?.let { name -> DocKind.entries.firstOrNull { it.name == name } },
        sort = savedState.get<String>(KEY_SORT)?.let(DocumentSort::valueOf) ?: DocumentSort.DATE_MODIFIED,
        grid = savedState.get<Boolean>(KEY_GRID) == true
    )
) {
    private val favorites = observeFavorites()
    private var documents: List<LibraryDocument> = emptyList()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch { favorites.collect { publish() } }
    }

    /** Re-reads the library; the list on screen stays up meanwhile. Called on every resume. */
    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launch(onError = { setState { copy(loading = false) } }) {
            val library = loadLibrary()
            documents = library.documents
            setState { copy(access = library.access, loading = false) }
            publish()
        }
    }

    fun onKindSelected(kind: DocKind?) {
        if (kind == currentState.kind) return
        savedState[KEY_KIND] = kind?.name
        setState { copy(kind = kind) }
        publish()
    }

    fun onQueryChanged(query: String) {
        if (query == currentState.query) return
        setState { copy(query = query) }
        publish()
    }

    fun onSortSelected(sort: DocumentSort) {
        savedState[KEY_SORT] = sort.name
        setState { copy(sort = sort) }
        publish()
    }

    fun onViewModeSelected(grid: Boolean) {
        savedState[KEY_GRID] = grid
        setState { copy(grid = grid) }
    }

    fun onStarClicked(document: LibraryDocument) = launch { toggleFavorite(document) }

    fun onDocumentClicked(document: LibraryDocument) {
        if (currentState.selectionMode) toggleSelection(document) else sendEvent(HomeUiEvent.Open(document))
    }

    fun onDocumentLongClicked(document: LibraryDocument) {
        if (!currentState.selectionMode) setState { copy(selectionMode = true, selected = emptySet()) }
        toggleSelection(document)
    }

    fun onSelectModeClicked() {
        if (currentState.items.isEmpty()) return
        setState { copy(selectionMode = true, selected = emptySet()) }
        publish()
    }

    /** True when it consumed the back press. */
    fun onBack(): Boolean {
        if (!currentState.selectionMode) return false
        exitSelection()
        return true
    }

    fun exitSelection() {
        setState { copy(selectionMode = false, selected = emptySet()) }
        publish()
    }

    fun onShareSelected() {
        val docs = selectedDocuments()
        if (docs.isNotEmpty()) sendEvent(HomeUiEvent.Share(docs))
    }

    fun onDeleteSelected() {
        val count = currentState.selected.size
        if (count > 0) sendEvent(HomeUiEvent.ConfirmDelete(count))
    }

    fun onDeleteConfirmed() = launch {
        val docs = selectedDocuments()
        val failed = docs.count { !deleteDocument(it) }
        exitSelection()
        if (failed > 0) sendEvent(HomeUiEvent.ShowMessage(UiText.res(R.string.home_delete_many_failed, failed)))
        loadJob?.cancel()
        refresh()
    }

    private fun toggleSelection(document: LibraryDocument) {
        val selected = currentState.selected.let { if (document.ref in it) it - document.ref else it + document.ref }
        setState { copy(selected = selected) }
        publish()
    }

    private fun selectedDocuments(): List<LibraryDocument> =
        documents.filter { it.ref in currentState.selected }

    /** Rebuilds the visible rows from the library, filters, favorites and selection. */
    private fun publish() {
        val state = currentState
        val starred = favorites.value.mapTo(HashSet()) { it.ref }
        val visible = filterDocuments(documents, state.kind, state.query, state.sort)
        val selected = state.selected.intersect(visible.mapTo(HashSet()) { it.ref })
        val items = visible.map {
            DocumentItem(it, it.ref in starred, state.selectionMode, it.ref in selected)
        }
        setState { copy(items = items, selected = selected) }
    }

    private companion object {
        const val KEY_KIND = "kind"
        const val KEY_SORT = "sort"
        const val KEY_GRID = "grid"
    }
}
