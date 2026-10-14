package com.chethan616.clearpdf.domain.usecase.library

import com.chethan616.clearpdf.domain.model.DocumentSort
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.utils.DocKind
import javax.inject.Inject

/** Applies the Home file-type tab (null = All), the search query and the sort order. */
class FilterDocumentsUseCase @Inject constructor() {
    operator fun invoke(
        documents: List<LibraryDocument>,
        kind: DocKind?,
        query: String,
        sort: DocumentSort
    ): List<LibraryDocument> {
        val q = query.trim()
        val filtered = documents.filter {
            (kind == null || it.kind == kind) && (q.isEmpty() || it.name.contains(q, ignoreCase = true))
        }
        return when (sort) {
            DocumentSort.DATE_MODIFIED -> filtered.sortedByDescending { it.modifiedAt }
            DocumentSort.NAME -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            DocumentSort.SIZE -> filtered.sortedByDescending { it.sizeBytes }
        }
    }
}
