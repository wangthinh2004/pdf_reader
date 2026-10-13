package com.chethan616.clearpdf.domain.repository

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.model.OutputDocument

/** Reads document metadata and creates output files in the user's save location. */
interface DocumentRepository {
    /** Keeps read access across restarts and returns name ("Unknown.pdf" if the provider gives none) and size. */
    suspend fun open(ref: DocumentRef): DocumentInfo

    /** Creates `<prefix>_<yyyyMMdd_HHmmss>.<extension>` in the chosen folder, falling back to Downloads. */
    suspend fun createOutput(prefix: String, extension: String, mimeType: String): OutputDocument
}
