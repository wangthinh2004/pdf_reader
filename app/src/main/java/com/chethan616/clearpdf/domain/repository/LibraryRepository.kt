package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.model.OutputDocument

/** Documents on the device's shared storage and the file operations Home offers on them. */
interface LibraryRepository {
    fun access(): LibraryAccess

    /** Needs [LibraryAccess.GRANTED]. */
    suspend fun deviceDocuments(): List<LibraryDocument>

    /** Renames in place to [newName] (with extension). Returns the renamed document. */
    suspend fun rename(document: LibraryDocument, newName: String): LibraryDocument

    /** Returns false (never throws) when the storage provider refuses. */
    suspend fun delete(document: LibraryDocument): Boolean

    /** Copies the file into the user's save location. */
    suspend fun duplicate(document: LibraryDocument): OutputDocument
}
