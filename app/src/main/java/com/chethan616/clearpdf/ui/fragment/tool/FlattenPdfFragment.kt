package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolSimpleBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.FlattenPdfViewModel
import com.chethan616.clearpdf.ui.viewmodel.FlattenUiState

class FlattenPdfFragment : ToolFragment<ToolSimpleBinding>() {

    override val titleRes = R.string.tool_flatten

    private val vm: FlattenPdfViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolSimpleBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_flatten)
        ToolUi.hero(
            body.hero, R.drawable.ic_layers, accent,
            getString(R.string.tool_flatten), getString(R.string.flatten_desc),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.apply(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: FlattenUiState) {
        val accent = requireContext().color(R.color.tool_flatten)
        ToolUi.file(body.file, state.sourceName.takeIf { state.sourceUri != null }, getString(R.string.flatten_hint), accent)
        body.run.isVisible = state.sourceUri != null
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_layers,
            getString(if (state.isProcessing) R.string.flatten_working else R.string.flatten_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
