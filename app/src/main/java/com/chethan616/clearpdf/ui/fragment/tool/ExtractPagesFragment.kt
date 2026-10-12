package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolExtractPagesBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.viewmodel.ExtractPagesUiState
import com.chethan616.clearpdf.ui.viewmodel.ExtractPagesViewModel

class ExtractPagesFragment : ToolFragment<ToolExtractPagesBinding>() {

    override val titleRes = R.string.tool_extract_pages

    private val vm: ExtractPagesViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolExtractPagesBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_extract_pages)
        ToolUi.hero(
            body.hero, R.drawable.ic_content_cut, accent,
            getString(R.string.tool_extract_pages), getString(R.string.tool_extract_pages_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.labelIcon.tint(accent)
        body.rangeLayout.hint = getString(R.string.extract_pages_hint)
        body.range.doAfterTextChanged { vm.onRangeChange(it?.toString().orEmpty()) }
        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.apply(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: ExtractPagesUiState) {
        val accent = requireContext().color(R.color.tool_extract_pages)
        val hasFile = state.sourceUri != null
        body.options.isVisible = hasFile
        body.run.isVisible = hasFile
        body.total.text = getString(R.string.extract_pages_total, state.pageCount)
        body.sourceName.text = state.sourceName
        ToolUi.syncText(body.range, state.rangeText)
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_content_cut,
            getString(if (state.isProcessing) R.string.extract_pages_working else R.string.extract_pages_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
