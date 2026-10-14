package com.chethan616.clearpdf.presentation.feature.collection

import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.domain.usecase.library.ObserveFavoritesUseCase
import com.chethan616.clearpdf.domain.usecase.library.ToggleFavoriteUseCase
import com.chethan616.clearpdf.presentation.feature.home.DocumentItem
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    observeFavorites: ObserveFavoritesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase
) : CollectionViewModel() {

    init {
        viewModelScope.launch {
            observeFavorites().collect { list ->
                setState { copy(loading = false, items = list.map { DocumentItem(it, favorite = true) }) }
            }
        }
    }

    /** Favorites are observed, so there's nothing to reload. */
    override fun refresh() = Unit

    override fun onStarClicked(document: LibraryDocument) {
        launch { toggleFavorite(document) }
    }
}

@AndroidEntryPoint
class FavoritesFragment : DocumentCollectionFragment() {
    override val viewModel: FavoritesViewModel by viewModels()
    override val title = R.string.favorites_title
    override val summary = R.plurals.favorites_summary
    override val emptyTitle = R.string.favorites_empty_title
    override val emptyBody = R.string.favorites_empty_body
}
