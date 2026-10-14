package com.chethan616.clearpdf.presentation.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.DialogRenameBinding
import com.chethan616.clearpdf.databinding.SheetDocumentActionsBinding
import com.chethan616.clearpdf.databinding.ViewSheetActionBinding
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.presentation.common.formatDocumentDate
import com.chethan616.clearpdf.presentation.common.formatDocumentSize
import com.chethan616.clearpdf.presentation.common.metaLine
import com.chethan616.clearpdf.presentation.common.style
import com.chethan616.clearpdf.ui.common.DocumentActions
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * File actions sheet ("More" in Figma). Rename, delete and copy run in [DocumentActionsViewModel];
 * when the file changes the sheet posts [REQUEST_KEY] so the host reloads, then closes.
 */
@AndroidEntryPoint
class DocumentActionsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetDocumentActionsBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val viewModel: DocumentActionsViewModel by viewModels()

    override fun getTheme() = R.style.ThemeOverlay_DS_BottomSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetDocumentActionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        val doc = viewModel.state.value.document
        val ctx = requireContext()
        val style = doc.kind.style()
        binding.icon.setImageResource(style.icon)
        binding.icon.imageTintList = ContextCompat.getColorStateList(ctx, style.iconTint)
        binding.name.text = doc.name
        binding.meta.text = doc.metaLine(ctx)
        binding.star.setOnClickListener { viewModel.onStarClicked() }

        binding.actionShare.setup(R.drawable.ic_ios_share, R.string.doc_share) {
            DocumentActions.share(ctx, doc.ref.toUri())
            dismiss()
        }
        binding.actionRename.setup(R.drawable.ic_edit, R.string.doc_rename) { showRename(doc) }
        binding.actionDetails.setup(R.drawable.ic_info, R.string.doc_details) { showDetails(doc) }
        binding.actionMove.setup(R.drawable.ic_folder_open, R.string.doc_move) {
            Toast.makeText(ctx, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        binding.actionDelete.setup(R.drawable.ic_delete_outline, R.string.doc_delete) { confirmDelete(doc) }
        binding.actionCopy.setup(R.drawable.ic_content_copy, R.string.doc_save_copy) { viewModel.onSaveCopy() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::handle) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: DocumentActionsUiState) {
        val ctx = requireContext()
        binding.star.setImageResource(if (state.favorite) R.drawable.ic_ds_star_on else R.drawable.ic_ds_star_off)
        binding.star.imageTintList = ContextCompat.getColorStateList(
            ctx, if (state.favorite) R.color.ds_primary else R.color.ds_text_secondary
        )
        binding.star.contentDescription = getString(if (state.favorite) R.string.doc_unstar else R.string.doc_star)
        listOf(binding.actionRename, binding.actionDelete, binding.actionCopy).forEach {
            it.root.isEnabled = !state.busy
            it.root.alpha = if (state.busy) 0.5f else 1f
        }
    }

    private fun handle(event: DocumentActionsUiEvent) {
        when (event) {
            is DocumentActionsUiEvent.Done -> {
                setFragmentResult(REQUEST_KEY, bundleOf(KEY_MESSAGE to event.message?.resolve(requireContext())))
                dismiss()
            }
            is DocumentActionsUiEvent.ShowMessage ->
                Toast.makeText(requireContext(), event.text.resolve(requireContext()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun ViewSheetActionBinding.setup(icon: Int, label: Int, onClick: () -> Unit) {
        this.icon.setImageResource(icon)
        this.label.setText(label)
        root.setOnClickListener { onClick() }
    }

    private fun showRename(doc: LibraryDocument) {
        val dialogBinding = DialogRenameBinding.inflate(layoutInflater)
        val extension = doc.name.substringAfterLast('.', "")
        val baseName = if (extension.isEmpty()) doc.name else doc.name.substringBeforeLast('.')
        dialogBinding.nameLayout.suffixText = extension.takeIf { it.isNotEmpty() }?.let { ".$it" }
        dialogBinding.name.setText(baseName)
        dialogBinding.name.setSelection(0, baseName.length)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.doc_rename_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.doc_rename) { _, _ -> viewModel.onRename(dialogBinding.name.text.toString()) }
            .create()
        dialogBinding.name.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            viewModel.onRename(dialogBinding.name.text.toString())
            dialog.dismiss()
            true
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        dialogBinding.name.requestFocus()
    }

    private fun confirmDelete(doc: LibraryDocument) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.doc_delete_title)
            .setMessage(getString(R.string.doc_delete_body, doc.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.doc_delete) { _, _ -> viewModel.onDeleteConfirmed() }
            .show()
    }

    private fun showDetails(doc: LibraryDocument) {
        val ctx = requireContext()
        val unknown = getString(R.string.doc_unknown)
        val location = doc.ref.toUri().let { it.path?.let(android.net.Uri::decode) ?: it.toString() }
        val rows = listOf(
            R.string.doc_detail_type to doc.kind.style().badge,
            R.string.doc_detail_size to (formatDocumentSize(ctx, doc.sizeBytes) ?: unknown),
            R.string.doc_detail_modified to formatDocumentDate(ctx, doc.modifiedAt),
            R.string.doc_detail_location to location
        ).joinToString("\n\n") { (label, value) -> "${getString(label)}\n$value" }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(doc.name)
            .setMessage(rows)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    companion object {
        const val TAG = "document_actions"
        const val REQUEST_KEY = "document_actions_result"
        const val KEY_MESSAGE = "message"

        fun show(manager: FragmentManager, document: LibraryDocument) {
            if (manager.findFragmentByTag(TAG) != null) return
            DocumentActionsSheet().apply {
                arguments = bundleOf(
                    DocumentActionsViewModel.ARG_URI to document.ref.value,
                    DocumentActionsViewModel.ARG_NAME to document.name,
                    DocumentActionsViewModel.ARG_SIZE to document.sizeBytes,
                    DocumentActionsViewModel.ARG_MODIFIED to document.modifiedAt
                )
            }.show(manager, TAG)
        }
    }
}
