package com.chethan616.clearpdf.domain.usecase.document

import com.chethan616.clearpdf.domain.model.DocumentInfo
import com.chethan616.clearpdf.domain.model.DocumentRef
import com.chethan616.clearpdf.domain.repository.DocumentRepository
import javax.inject.Inject

/** A document the user just picked: keep access to it and read its name and size. */
class OpenDocumentUseCase @Inject constructor(private val documents: DocumentRepository) {
    suspend operator fun invoke(ref: DocumentRef): DocumentInfo = documents.open(ref)
}
