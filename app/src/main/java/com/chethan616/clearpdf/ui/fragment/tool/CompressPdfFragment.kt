package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ToolCompressBinding
import com.chethan616.clearpdf.domain.usecase.CompressPdfUseCase
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.viewmodel.CompressPdfUiState
import com.chethan616.clearpdf.ui.viewmodel.CompressPdfViewModel

class CompressPdfFragment : ToolFragment<ToolCompressBinding>() {

    override val titleRes = R.string.tool_compress

    private val vm: CompressPdfViewModel by viewModels {
        viewModelFactory { CompressPdfViewModel(CompressPdfUseCase(PdfServiceLocator.pdfCompressor)) }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolCompressBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_compress)
        ToolUi.hero(
            body.hero, R.drawable.ic_compress, accent,
            getString(R.string.tool_compress), getString(R.string.tool_compress_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.qualityIcon.tint(accent)
        body.qualityValue.setTextColor(accent)
        body.qualityValue.tintBackground(ctx.accentWash(accent))
        body.qualitySlider.addOnChangeListener { _, value, fromUser -> if (fromUser) vm.onQualitySliderChanged(value) }
        ToolUi.primaryButton(body.compress, accent)
        body.compress.setOnClickListener { vm.onCompress(requireContext()) }

        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: CompressPdfUiState) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_compress)
        val hasFile = state.sourceFileName.isNotEmpty()
        ToolUi.file(body.file, state.sourceFileName, getString(R.string.size_kb, state.originalSizeBytes / 1024), accent)
        body.options.isVisible = hasFile
        body.compress.isVisible = hasFile
        if (body.qualitySlider.value != state.qualitySlider) body.qualitySlider.value = state.qualitySlider.coerceIn(0f, 1f)
        body.qualityValue.text = "${(state.qualitySlider * 100).toInt()}%"
        body.estimate.isVisible = state.estimatedSizeBytes > 0
        if (state.estimatedSizeBytes > 0) {
            body.estimate.text = "${getString(R.string.estimated_output)}  ${state.originalSizeBytes / 1024} KB  →  " +
                "${getString(R.string.approximation)} ${state.estimatedSizeBytes / 1024} KB"
        }
        ToolUi.setBusy(
            body.compress, state.isCompressing, R.drawable.ic_compress,
            getString(if (state.isCompressing) R.string.compress_working else R.string.compress_action)
        )

        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
