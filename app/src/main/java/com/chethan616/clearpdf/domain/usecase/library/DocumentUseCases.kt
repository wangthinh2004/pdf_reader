package com.chethan616.clearpdf.domain.usecase.library

import com.chethan616.clearpdf.core.result.AppError
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.FavoritesRepository
import com.chethan616.clearpdf.domain.repository.LibraryRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Stars or un-stars a document. Returns true when it is a favorite afterwards. */
class ToggleFavoriteUseCase @Inject constructor(private val favorites: FavoritesRepository) {
    suspend operator fun invoke(document: LibraryDocument): Boolean {
        val starred = favorites.favorites.value.any { it.ref == document.ref }
        if (starred) favorites.remove(document.ref) else favorites.add(document)
        return !starred
    }
}

/** Starred documents; every list observes this to draw its stars. */
class ObserveFavoritesUseCase @Inject constructor(private val favorites: FavoritesRepository) {
    operator fun invoke(): StateFlow<List<LibraryDocument>> = favorites.favorites
}

/** Recent files for the Recent tab. */
class GetRecentDocumentsUseCase @Inject constructor(private val recents: RecentFilesRepository) {
    suspend operator fun invoke(): List<LibraryDocument> = recents.recents()
}

/**
 * Renames a document. [baseName] is the name without extension; the original extension is kept
 * so the file still opens in the right viewer. A starred file stays starred under its new name.
 */
class RenameDocumentUseCase @Inject constructor(
    private val library: LibraryRepository,
    private val favorites: FavoritesRepository,
    private val recents: RecentFilesRepository
) {
    suspend operator fun invoke(document: LibraryDocument, baseName: String): LibraryDocument {
        val trimmed = baseName.trim()
        if (trimmed.isEmpty() || trimmed.any { it in INVALID_CHARS }) throw AppError.InvalidName()
        val extension = document.name.substringAfterLast('.', "")
        val newName = if (extension.isEmpty()) trimmed else "$trimmed.$extension"
        if (newName == document.name) return document
        val renamed = library.rename(document, newName)
        favorites.replace(document.ref, renamed)
        recents.remove(document.ref)
        return renamed
    }

    private companion object {
        const val INVALID_CHARS = "/\\:*?\"<>|"
    }
}

/** Deletes the file and forgets it in recents and favorites. Returns false when storage refused. */
class DeleteDocumentUseCase @Inject constructor(
    private val library: LibraryRepository,
    private val favorites: FavoritesRepository,
    private val recents: RecentFilesRepository
) {
    suspend operator fun invoke(document: LibraryDocument): Boolean {
        if (!library.delete(document)) return false
        favorites.remove(document.ref)
        recents.remove(document.ref)
        return true
    }
}

/** "Save as Copy": copies the file into the user's save location. */
class DuplicateDocumentUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(document: LibraryDocument): OutputDocument = library.duplicate(document)
}
