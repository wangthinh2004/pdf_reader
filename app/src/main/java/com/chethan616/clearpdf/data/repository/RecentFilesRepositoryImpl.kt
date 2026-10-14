package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import com.chethan616.clearpdf.utils.docKindOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RecentFilesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) : RecentFilesRepository {
    override suspend fun add(document: OutputDocument, sizeBytes: Long): Unit = withContext(io) {
        RecentFilesManager.addRecent(
            context,
            RecentFile(
                name = document.name,
                uriString = document.ref.value,
                timestamp = System.currentTimeMillis(),
                sizeBytes = sizeBytes
            )
        )
    }

    override suspend fun recents(): List<LibraryDocument> = withContext(io) {
        RecentFilesManager.getRecents(context).map {
            LibraryDocument(
                ref = DocumentRef(it.uriString),
                name = it.name,
                sizeBytes = it.sizeBytes,
                modifiedAt = it.timestamp,
                kind = docKindOf(it.name)
            )
        }
    }

    override suspend fun remove(ref: DocumentRef): Unit = withContext(io) {
        RecentFilesManager.removeRecent(context, ref.toUri())
    }
}
