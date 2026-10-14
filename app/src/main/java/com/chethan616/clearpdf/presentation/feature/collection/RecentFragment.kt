package com.chethan616.clearpdf.presentation.feature.collection

import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.usecase.library.GetRecentDocumentsUseCase
import com.chethan616.clearpdf.domain.usecase.library.ObserveFavoritesUseCase
import com.chethan616.clearpdf.domain.usecase.library.ToggleFavoriteUseCase
import com.chethan616.clearpdf.presentation.feature.home.DocumentItem
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecentViewModel @Inject constructor(
    private val getRecents: GetRecentDocumentsUseCase,
    observeFavorites: ObserveFavoritesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase
) : CollectionViewModel() {

    private val favorites = observeFavorites()
    private var recents: List<LibraryDocument> = emptyList()

    init {
        viewModelScope.launch { favorites.collect { publish() } }
    }

    override fun refresh() {
        launch(onError = { setState { copy(loading = false) } }) {
            recents = getRecents()
            publish()
        }
    }

    override fun onStarClicked(document: LibraryDocument) {
        launch { toggleFavorite(document) }
    }

    private fun publish() {
        val starred = favorites.value.mapTo(HashSet()) { it.ref }
        setState { copy(loading = false, items = recents.map { DocumentItem(it, it.ref in starred) }) }
    }
}

@AndroidEntryPoint
class RecentFragment : DocumentCollectionFragment() {
    override val viewModel: RecentViewModel by viewModels()
    override val title = R.string.recent_title
    override val summary = R.plurals.home_summary_recent
    override val emptyTitle = R.string.recent_empty_title
    override val emptyBody = R.string.recent_empty_body
}
