package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ItemReorderRowBinding
import com.chethan616.clearpdf.databinding.ToolMergeBinding
import com.chethan616.clearpdf.domain.usecase.MergePdfUseCase
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.viewmodel.MergePdfUiState
import com.chethan616.clearpdf.ui.viewmodel.MergePdfViewModel

class MergePdfFragment : ToolFragment<ToolMergeBinding>() {

    override val titleRes = R.string.tool_merge

    private val vm: MergePdfViewModel by viewModels {
        viewModelFactory { MergePdfViewModel(MergePdfUseCase(PdfServiceLocator.pdfMerger)) }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addFiles(requireContext(), uris)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolMergeBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_merge)
        ToolUi.hero(
            body.hero, R.drawable.ic_call_merge, accent,
            getString(R.string.tool_merge), getString(R.string.tool_merge_description),
            getString(R.string.add_files)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.hero.primary.setIconResource(R.drawable.ic_add_circle_outline)
        ToolUi.primaryButton(body.merge, accent)
        body.merge.setOnClickListener { vm.onMerge(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: MergePdfUiState) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_merge)
        val hasFiles = state.selectedFiles.isNotEmpty()
        val canMerge = state.selectedUris.size >= 2 && !state.isMerging
        body.filesCard.isVisible = hasFiles
        body.filesCount.text = getString(R.string.files_selected, state.selectedFiles.size)
        body.files.removeAllViews()
        state.selectedFiles.forEachIndexed { index, name ->
            val row = ItemReorderRowBinding.inflate(layoutInflater, body.files, true)
            row.icon.tint(accent)
            row.name.text = name
            row.up.isEnabled = index > 0
            row.down.isEnabled = index < state.selectedFiles.lastIndex
            row.up.setOnClickListener { vm.moveFile(index, index - 1) }
            row.down.setOnClickListener { vm.moveFile(index, index + 1) }
            row.remove.setOnClickListener { vm.onRemoveFile(index) }
        }
        body.needTwo.isVisible = hasFiles && state.selectedFiles.size < 2
        body.merge.isVisible = hasFiles
        ToolUi.setEnabled(body.merge, canMerge || state.isMerging)
        ToolUi.setBusy(
            body.merge, state.isMerging, R.drawable.ic_merge_type,
            getString(if (state.isMerging) R.string.merging else R.string.merge_now)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
