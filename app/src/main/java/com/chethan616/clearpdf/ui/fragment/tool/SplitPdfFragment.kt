package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ToolSplitBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.SplitMode
import com.chethan616.clearpdf.ui.viewmodel.SplitPdfUiState
import com.chethan616.clearpdf.ui.viewmodel.SplitPdfViewModel

class SplitPdfFragment : ToolFragment<ToolSplitBinding>() {

    override val titleRes = R.string.tool_split

    private val vm: SplitPdfViewModel by viewModels { viewModelFactory { SplitPdfViewModel(PdfServiceLocator.pdfSplitter) } }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    private lateinit var grid: PageGridAdapter

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolSplitBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_split)
        ToolUi.hero(
            body.hero, R.drawable.ic_call_split, accent,
            getString(R.string.tool_split), getString(R.string.tool_split_description),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }

        body.mode.first.setText(R.string.extract_selected_action)
        body.mode.second.setText(R.string.split_all_action)
        ToolUi.segment(body.mode.first, accent)
        ToolUi.segment(body.mode.second, accent)
        body.mode.group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) vm.onModeChanged(if (id == body.mode.second.id) SplitMode.SPLIT_ALL else SplitMode.EXTRACT_SELECTED)
        }

        ToolUi.quickChips(body.pages.quick, accent, listOf(
            getString(R.string.split_quick_all) to vm::onSelectAllPages,
            getString(R.string.split_quick_odd) to vm::onSelectOddPages,
            getString(R.string.split_quick_even) to vm::onSelectEvenPages,
            getString(R.string.viewer_clear) to vm::onClearSelection
        ))
        grid = PageGridAdapter(accent, vm::onToggleSelectedPage)
        PageGridAdapter.attach(body.pages.grid, grid)

        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.onRunPrimaryAction(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: SplitPdfUiState) {
        val accent = requireContext().color(R.color.tool_split)
        val hasFile = state.sourceFileName.isNotEmpty()
        ToolUi.file(body.file, state.sourceFileName, getString(R.string.create_pages, state.pageCount), accent)
        body.options.isVisible = hasFile

        val extract = state.mode == SplitMode.EXTRACT_SELECTED
        val checkedId = if (extract) body.mode.first.id else body.mode.second.id
        if (body.mode.group.checkedButtonId != checkedId) body.mode.group.check(checkedId)
        body.pages.root.isVisible = extract
        body.splitAllHint.isVisible = !extract
        body.pages.count.text = getString(R.string.pages_selected_count, state.selectedPages.size, state.pageCount)
        grid.submit(state.pageCount, state.pageThumbnails, state.selectedPages)

        val label = when {
            !extract && state.isSplitting -> R.string.split_all_working
            !extract -> R.string.split_all_pages_action
            state.isSplitting -> R.string.extract_pages_working
            else -> R.string.extract_pages_selected_action
        }
        ToolUi.setBusy(body.run, state.isSplitting, if (extract) R.drawable.ic_content_cut else R.drawable.ic_call_split, getString(label))

        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
