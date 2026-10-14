package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.repository.FavoritesRepository
import com.chethan616.clearpdf.utils.docKindOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Favorites as JSON in SharedPreferences; the in-memory list is the source every screen observes. */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : FavoritesRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    private val _favorites = MutableStateFlow(read())
    override val favorites: StateFlow<List<LibraryDocument>> = _favorites.asStateFlow()

    override suspend fun add(document: LibraryDocument) = update { list ->
        listOf(document) + list.filterNot { it.ref == document.ref }
    }

    override suspend fun remove(ref: DocumentRef) = update { list -> list.filterNot { it.ref == ref } }

    override suspend fun replace(old: DocumentRef, renamed: LibraryDocument) = update { list ->
        list.map { if (it.ref == old) renamed else it }
    }

    private suspend fun update(change: (List<LibraryDocument>) -> List<LibraryDocument>) = writeLock.withLock {
        val next = change(_favorites.value)
        if (next == _favorites.value) return@withLock
        _favorites.value = next
        withContext(io) {
            prefs.edit().putString(KEY, json.encodeToString(next.map { it.toStored() })).apply()
        }
    }

    private fun read(): List<LibraryDocument> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        json.decodeFromString<List<StoredFavorite>>(raw).map { it.toDocument() }
    }.getOrDefault(emptyList())

    @Serializable
    private data class StoredFavorite(val uri: String, val name: String, val size: Long, val modified: Long)

    private fun LibraryDocument.toStored() = StoredFavorite(ref.value, name, sizeBytes, modifiedAt)

    private fun StoredFavorite.toDocument() =
        LibraryDocument(DocumentRef(uri), name, size, modified, docKindOf(name))

    private companion object {
        const val PREFS_NAME = "clearpdf_favorites"
        const val KEY = "favorites"
    }
}
