package com.chethan616.clearpdf.domain.model

/** Opaque reference to a document (a content/file URI string at the data layer). */
@JvmInline
value class DocumentRef(val value: String)

data class DocumentInfo(
    val ref: DocumentRef,
    val name: String,
    /** -1 when the provider doesn't report a size. */
    val sizeBytes: Long
)

/** A newly created, still-empty output file and where the user will find it. */
data class OutputDocument(
    val ref: DocumentRef,
    val name: String,
    /** "Downloads (default)" or the user's chosen folder, as shown in messages. */
    val locationLabel: String
)
