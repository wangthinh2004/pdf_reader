package com.chethan616.clearpdf.data.repository

import android.content.Context
import com.chethan616.clearpdf.core.dispatchers.IoDispatcher
import com.chethan616.clearpdf.domain.model.OutputDocument
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
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
}
