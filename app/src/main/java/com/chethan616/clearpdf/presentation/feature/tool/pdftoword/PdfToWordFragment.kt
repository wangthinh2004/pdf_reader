package com.chethan616.clearpdf.presentation.feature.tool.pdftoword

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.ToolPdfToWordBinding
import com.chethan616.clearpdf.presentation.base.BaseToolFragment
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PdfToWordFragment : BaseToolFragment<ToolPdfToWordBinding>(ToolPdfToWordBinding::inflate) {

    override val titleRes = R.string.tool_pdf_to_word

    private val viewModel: PdfToWordViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toDocumentRef())
    }

    private val accent get() = requireContext().color(R.color.tool_pdf_to_word)

    override fun initViews(savedInstanceState: Bundle?) {
        ToolUi.hero(
            body.hero, R.drawable.ic_format_word, accent,
            getString(R.string.tool_pdf_to_word), getString(R.string.pdf_to_word_hero_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        ToolUi.primaryButton(body.convert, accent)
        body.openOutput.root.setText(R.string.pdf_to_word_open)
    }

    override fun initListeners() {
        body.convert.setOnClickListener { viewModel.onConvertClicked() }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted(::render)
        viewModel.events.collectOnStarted { event ->
            when (event) {
                is PdfToWordUiEvent.ShowStatus -> showStatus(body.status, event.text, event.isError)
            }
        }
    }

    private fun render(state: PdfToWordUiState) {
        val source = state.source
        ToolUi.file(body.file, source?.name, getString(R.string.size_kb, (source?.sizeBytes ?: 0) / 1024), accent)
        body.note.isVisible = source != null
        body.convert.isVisible = source != null
        val progress = state.progress
        val label = when {
            !state.isConverting -> getString(R.string.pdf_to_word_action)
            progress == null -> getString(R.string.pdf_to_word_preparing)
            progress.first >= progress.second -> getString(R.string.pdf_to_word_saving)
            else -> getString(R.string.pdf_to_word_working, progress.first + 1, progress.second)
        }
        ToolUi.setBusy(body.convert, state.isConverting, R.drawable.ic_format_word, label)
        ToolUi.openOutput(body.openOutput.root, state.output?.ref?.toUri(), accent) { uri ->
            AppNavigator.openDocument(findNavController(), requireContext(), uri, state.output?.name)
        }
    }
}
