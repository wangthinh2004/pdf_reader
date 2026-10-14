package com.chethan616.clearpdf.presentation.feature.collection

import android.os.Bundle
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.FragmentDocumentCollectionBinding
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.presentation.base.BaseFragment
import com.chethan616.clearpdf.presentation.base.BaseViewModel
import com.chethan616.clearpdf.presentation.base.UiEvent
import com.chethan616.clearpdf.presentation.base.UiState
import com.chethan616.clearpdf.presentation.feature.home.DocumentActionsSheet
import com.chethan616.clearpdf.presentation.feature.home.DocumentAdapter
import com.chethan616.clearpdf.presentation.feature.home.DocumentItem
import com.chethan616.clearpdf.presentation.feature.home.DocumentListener
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.google.android.material.snackbar.Snackbar

data class CollectionUiState(val loading: Boolean = true, val items: List<DocumentItem> = emptyList()) : UiState

/** A list of documents with stars and the file actions sheet, shared by Recent and Favorites. */
abstract class CollectionViewModel : BaseViewModel<CollectionUiState, UiEvent>(CollectionUiState()) {
    abstract fun refresh()
    abstract fun onStarClicked(document: LibraryDocument)
}

/** Recent and Favorites tabs: title, count and the Home file rows. */
abstract class DocumentCollectionFragment :
    BaseFragment<FragmentDocumentCollectionBinding>(FragmentDocumentCollectionBinding::inflate), DocumentListener {

    protected abstract val viewModel: CollectionViewModel
    @get:StringRes protected abstract val title: Int
    @get:PluralsRes protected abstract val summary: Int
    @get:StringRes protected abstract val emptyTitle: Int
    @get:StringRes protected abstract val emptyBody: Int

    private lateinit var adapter: DocumentAdapter

    override fun initViews(savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true)
        binding.title.setText(title)
        binding.emptyTitle.setText(emptyTitle)
        binding.emptyBody.setText(emptyBody)
        adapter = DocumentAdapter(this, viewLifecycleOwner.lifecycleScope)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        childFragmentManager.setFragmentResultListener(DocumentActionsSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            result.getString(DocumentActionsSheet.KEY_MESSAGE)?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() }
            viewModel.refresh()
        }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted { state ->
            adapter.submitList(state.items)
            binding.summary.text = resources.getQuantityString(summary, state.items.size, state.items.size)
            binding.summary.isVisible = state.items.isNotEmpty()
            binding.empty.isVisible = !state.loading && state.items.isEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDocumentClick(document: LibraryDocument) =
        AppNavigator.openDocument(findNavController(), requireContext(), document.ref.toUri(), document.name)

    override fun onDocumentLongClick(document: LibraryDocument) = onMoreClick(document)

    override fun onStarClick(document: LibraryDocument) = viewModel.onStarClicked(document)

    override fun onMoreClick(document: LibraryDocument) = DocumentActionsSheet.show(childFragmentManager, document)
}
