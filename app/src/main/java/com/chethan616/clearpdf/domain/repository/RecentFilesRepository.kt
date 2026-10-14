package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.model.OutputDocument

/** Recently opened or saved files. */
interface RecentFilesRepository {
    suspend fun add(document: OutputDocument, sizeBytes: Long)

    /** Pinned first, then newest; empty when the user turned recent files off. */
    suspend fun recents(): List<LibraryDocument>

    suspend fun remove(ref: DocumentRef)
}
