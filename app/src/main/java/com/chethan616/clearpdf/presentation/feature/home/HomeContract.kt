package com.chethan616.clearpdf.presentation.feature.home

import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.DocumentSort
import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.common.UiText
import com.chethan616.clearpdf.utils.DocKind

/** Which full-screen message replaces the list. */
enum class HomeEmpty {
    /** No documents at all: "Your workspace starts here" + Create Now. */
    NO_DOCUMENTS,

    /** File access not granted: explain and ask. */
    NEEDS_ACCESS,

    /** Documents exist but the tab/search hides them all. */
    NO_MATCHES
}

data class HomeUiState(
    val loading: Boolean = true,
    val access: LibraryAccess = LibraryAccess.GRANTED,
    val items: List<DocumentItem> = emptyList(),
    val kind: DocKind? = null,
    val query: String = "",
    val sort: DocumentSort = DocumentSort.DATE_MODIFIED,
    val grid: Boolean = false,
    val selectionMode: Boolean = false,
    val selected: Set<DocumentRef> = emptySet()
) : UiState {
    /** Null while the list has something to show. */
    val empty: HomeEmpty?
        get() = when {
            loading || items.isNotEmpty() -> null
            access == LibraryAccess.NEEDS_PERMISSION -> HomeEmpty.NEEDS_ACCESS
            kind != null || query.isNotBlank() -> HomeEmpty.NO_MATCHES
            else -> HomeEmpty.NO_DOCUMENTS
        }
}

sealed interface HomeUiEvent : UiEvent {
    data class Open(val document: LibraryDocument) : HomeUiEvent
    data class ShowMessage(val text: UiText) : HomeUiEvent
    data class Share(val documents: List<LibraryDocument>) : HomeUiEvent
    data class ConfirmDelete(val count: Int) : HomeUiEvent
}
