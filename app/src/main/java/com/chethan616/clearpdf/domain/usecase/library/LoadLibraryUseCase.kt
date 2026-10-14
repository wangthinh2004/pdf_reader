package com.chethan616.clearpdf.domain.usecase.library

import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.repository.LibraryRepository
import com.chethan616.clearpdf.domain.repository.RecentFilesRepository
import javax.inject.Inject

/** What Home lists, and whether that is everything on the device. */
data class Library(val access: LibraryAccess, val documents: List<LibraryDocument>)

/**
 * Every document on the device when the app may list them. Where Android can't (Android 10) the
 * recent files stand in so Home still shows the user's work; without permission the list is empty.
 */
class LoadLibraryUseCase @Inject constructor(
    private val library: LibraryRepository,
    private val recents: RecentFilesRepository
) {
    suspend operator fun invoke(): Library = when (val access = library.access()) {
        LibraryAccess.GRANTED -> Library(access, library.deviceDocuments())
        LibraryAccess.UNSUPPORTED -> Library(access, recents.recents())
        LibraryAccess.NEEDS_PERMISSION -> Library(access, emptyList())
    }
}
