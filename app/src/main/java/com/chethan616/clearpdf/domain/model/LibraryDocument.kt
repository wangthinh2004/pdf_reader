package com.chethan616.clearpdf.domain.model

import com.chethan616.clearpdf.utils.DocKind

/** A document shown in the Home, Recent and Favorites lists. */
data class LibraryDocument(
    val ref: DocumentRef,
    val name: String,
    /** -1 when unknown. */
    val sizeBytes: Long,
    /** Epoch millis: last modified on the device, or when it was last opened for recents. */
    val modifiedAt: Long,
    val kind: DocKind
)

/** Home sort order. Dates and sizes sort newest/largest first, names A→Z. */
enum class DocumentSort { DATE_MODIFIED, NAME, SIZE }

/** Whether the app may list the documents on shared storage. */
enum class LibraryAccess {
    GRANTED,

    /** The user has to allow file access first. */
    NEEDS_PERMISSION,

    /** This Android release can't list other apps' documents (Android 10). */
    UNSUPPORTED
}
