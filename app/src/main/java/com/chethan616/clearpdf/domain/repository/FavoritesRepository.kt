package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.LibraryDocument
import kotlinx.coroutines.flow.StateFlow

/** Starred documents, kept on the device. Newest star first. */
interface FavoritesRepository {
    val favorites: StateFlow<List<LibraryDocument>>

    suspend fun add(document: LibraryDocument)

    suspend fun remove(ref: DocumentRef)

    /** Points an existing favorite at the renamed file. No-op when [old] isn't starred. */
    suspend fun replace(old: DocumentRef, renamed: LibraryDocument)
}
