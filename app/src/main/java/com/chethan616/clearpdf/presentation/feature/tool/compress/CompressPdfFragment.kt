package com.chethan616.clearpdf.presentation.feature.tool.compress

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.ToolCompressBinding
import com.chethan616.clearpdf.presentation.base.BaseToolFragment
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CompressPdfFragment : BaseToolFragment<ToolCompressBinding>(ToolCompressBinding::inflate) {

    override val titleRes = R.string.tool_compress

    private val viewModel: CompressPdfViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toDocumentRef())
    }

    private val accent get() = requireContext().color(R.color.tool_compress)

    override fun initViews(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        ToolUi.hero(
            body.hero, R.drawable.ic_compress, accent,
            getString(R.string.tool_compress), getString(R.string.tool_compress_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.qualityIcon.tint(accent)
        body.qualityValue.setTextColor(accent)
        body.qualityValue.tintBackground(ctx.accentWash(accent))
        ToolUi.primaryButton(body.compress, accent)
    }

    override fun initListeners() {
        body.qualitySlider.addOnChangeListener { _, value, fromUser -> if (fromUser) viewModel.onSliderChanged(value) }
        body.compress.setOnClickListener { viewModel.onCompressClicked() }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted(::render)
        viewModel.events.collectOnStarted { event ->
            when (event) {
                is CompressPdfUiEvent.ShowStatus -> showStatus(body.status, event.text, event.isError)
            }
        }
    }

    private fun render(state: CompressPdfUiState) {
        val source = state.source
        ToolUi.file(body.file, source?.name, getString(R.string.size_kb, (source?.sizeBytes ?: 0) / 1024), accent)
        body.options.isVisible = source != null
        body.compress.isVisible = source != null
        val slider = state.sliderFraction.coerceIn(0f, 1f)
        if (body.qualitySlider.value != slider) body.qualitySlider.value = slider
        body.qualityValue.text = "${(state.sliderFraction * 100).toInt()}%"
        body.estimate.isVisible = state.estimatedBytes > 0
        if (source != null && state.estimatedBytes > 0) {
            body.estimate.text = "${getString(R.string.estimated_output)}  ${source.sizeBytes / 1024} KB  →  " +
                "${getString(R.string.approximation)} ${state.estimatedBytes / 1024} KB"
        }
        ToolUi.setBusy(
            body.compress, state.isCompressing, R.drawable.ic_compress,
            getString(if (state.isCompressing) R.string.compress_working else R.string.compress_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.output?.ref?.toUri(), accent, ::viewOutput)
    }
}
