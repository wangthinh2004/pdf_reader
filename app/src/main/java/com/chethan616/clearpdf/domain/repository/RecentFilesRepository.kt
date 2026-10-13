package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.OutputDocument

/** Recent files list on Home. */
interface RecentFilesRepository {
    suspend fun add(document: OutputDocument, sizeBytes: Long)
}
