package com.chethan616.clearpdf.presentation.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.usecase.library.DeleteDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.library.DuplicateDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.library.ObserveFavoritesUseCase
import com.chethan616.clearpdf.domain.usecase.library.RenameDocumentUseCase
import com.chethan616.clearpdf.domain.usecase.library.ToggleFavoriteUseCase
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.utils.docKindOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentActionsUiState(
    val document: LibraryDocument,
    val favorite: Boolean = false,
    val busy: Boolean = false
) : UiState

sealed interface DocumentActionsUiEvent : UiEvent {
    /** The file changed (renamed, deleted, copied): tell the host, show [message], close. */
    data class Done(val message: UiText?) : DocumentActionsUiEvent
    data class ShowMessage(val text: UiText) : DocumentActionsUiEvent
}

@HiltViewModel
class DocumentActionsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    observeFavorites: ObserveFavoritesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val renameDocument: RenameDocumentUseCase,
    private val deleteDocument: DeleteDocumentUseCase,
    private val duplicateDocument: DuplicateDocumentUseCase
) : BaseViewModel<DocumentActionsUiState, DocumentActionsUiEvent>(
    DocumentActionsUiState(documentFrom(savedState))
) {
    init {
        viewModelScope.launch {
            observeFavorites().collect { list ->
                setState { copy(favorite = list.any { it.ref == document.ref }) }
            }
        }
    }

    fun onStarClicked() = launch { toggleFavorite(currentState.document) }

    fun onRename(baseName: String) = perform(R.string.doc_rename_failed) {
        renameDocument(currentState.document, baseName)
        DocumentActionsUiEvent.Done(null)
    }

    fun onDeleteConfirmed() = perform(R.string.doc_delete_failed) {
        if (deleteDocument(currentState.document)) {
            DocumentActionsUiEvent.Done(UiText.res(R.string.doc_deleted))
        } else {
            DocumentActionsUiEvent.ShowMessage(UiText.res(R.string.doc_delete_failed))
        }
    }

    fun onSaveCopy() = perform(R.string.doc_copy_failed) {
        val copy = duplicateDocument(currentState.document)
        DocumentActionsUiEvent.Done(UiText.res(R.string.doc_copied, copy.locationLabel))
    }

    /** Runs one file operation at a time; failures become [failure] (or the invalid-name hint). */
    private fun perform(failure: Int, block: suspend () -> DocumentActionsUiEvent) {
        if (currentState.busy) return
        setState { copy(busy = true) }
        launch(onError = { error ->
            setState { copy(busy = false) }
            val text = if (error is AppError.InvalidName) R.string.doc_rename_invalid else failure
            sendEvent(DocumentActionsUiEvent.ShowMessage(UiText.res(text)))
        }) {
            val event = block()
            setState { copy(busy = false) }
            sendEvent(event)
        }
    }

    companion object {
        const val ARG_URI = "uri"
        const val ARG_NAME = "name"
        const val ARG_SIZE = "size"
        const val ARG_MODIFIED = "modified"

        private fun documentFrom(savedState: SavedStateHandle): LibraryDocument {
            val name = savedState.get<String>(ARG_NAME).orEmpty()
            return LibraryDocument(
                ref = DocumentRef(savedState.get<String>(ARG_URI).orEmpty()),
                name = name,
                sizeBytes = savedState.get<Long>(ARG_SIZE) ?: -1L,
                modifiedAt = savedState.get<Long>(ARG_MODIFIED) ?: 0L,
                kind = docKindOf(name)
            )
        }
    }
}
