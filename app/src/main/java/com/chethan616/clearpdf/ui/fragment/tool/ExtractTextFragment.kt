package com.chethan616.clearpdf.ui.fragment.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ToolExtractTextBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.ExtractTextUiState
import com.chethan616.clearpdf.ui.viewmodel.ExtractTextViewModel

class ExtractTextFragment : ToolFragment<ToolExtractTextBinding>() {

    override val titleRes = R.string.tool_extract

    private val vm: ExtractTextViewModel by viewModels {
        viewModelFactory {
            ExtractTextViewModel(PdfServiceLocator.pdfConverter, PdfServiceLocator.ocrService, PdfServiceLocator.pdfViewer)
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolExtractTextBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_extract_text)
        ToolUi.primaryButton(body.pick, accent)
        ToolUi.primaryButton(body.recognize, accent)
        ToolUi.primaryButton(body.searchable, requireContext().color(R.color.app_success))
        body.pick.setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        body.copy.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.tool_extract), vm.uiState.value.text))
        }
        body.share.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, vm.uiState.value.text)
            }
            runCatching { startActivity(Intent.createChooser(send, getString(R.string.extract_share_text))) }
        }
        body.recognize.setOnClickListener { vm.recognizeText(requireContext()) }
        body.searchable.setOnClickListener { vm.makeSearchablePdf(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: ExtractTextUiState) {
        val hasText = state.text.isNotEmpty()
        body.copy.isVisible = hasText
        body.share.isVisible = hasText
        body.sourceName.text = state.sourceFileName
        body.sourceName.isVisible = state.sourceFileName.isNotBlank()
        body.error.text = state.errorMessage
        body.error.isVisible = state.errorMessage != null
        body.recognize.isVisible = state.canRecognize && !state.isRecognizing
        body.searchable.isVisible = state.canMakeSearchable
        ToolUi.setBusy(body.searchable, state.isSavingSearchable, null, getString(R.string.extract_make_searchable))
        body.searchableSaved.isVisible = state.searchableOutputUri != null
        body.searchableSaved.text = getString(R.string.extract_searchable_saved, state.searchableSavedLabel.orEmpty())

        val busy = state.isExtracting || state.isRecognizing
        body.text.isVisible = hasText && !busy
        if (body.text.text.toString() != state.text) body.text.text = state.text
        body.placeholder.isVisible = !hasText || busy
        body.spinner.isVisible = busy
        val progress = state.recognizeProgress
        body.placeholderText.text = when {
            state.isExtracting -> null
            state.isRecognizing && progress != null -> getString(R.string.extract_recognizing, progress.first + 1, progress.second)
            state.isRecognizing -> getString(R.string.extract_recognize_text)
            state.hasResult -> getString(R.string.extract_no_text)
            else -> getString(R.string.extract_pick_hint)
        }
    }
}
