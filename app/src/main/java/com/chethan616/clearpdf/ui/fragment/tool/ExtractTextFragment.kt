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
import com.chethan616.clearpdf.data.ai.GeminiSummarizer
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ToolExtractTextBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.ExtractTextUiState
import com.chethan616.clearpdf.ui.viewmodel.ExtractTextViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ExtractTextFragment : ToolFragment<ToolExtractTextBinding>() {

    /** Opened from the AI Summary tool: summarize as soon as the document's text is ready. */
    private val aiMode get() = arguments?.getBoolean(ARG_AI_MODE) == true

    /** The file the AI mode already asked to summarize, so a dismissed consent dialog isn't re-shown. */
    private var autoSummarizedUri: android.net.Uri? = null

    override val titleRes get() = if (aiMode) R.string.tool_ai_summary else R.string.tool_extract

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
        ToolUi.primaryButton(body.summarize, accent)
        body.summarize.setOnClickListener { requestSummary() }
        body.summaryCopy.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.ai_summary_title), vm.uiState.value.summary.orEmpty()))
        }
        collectWhileStarted(vm.uiState, ::render)
    }

    /** Text leaves the device only after the user has agreed once to sending it to Gemini. */
    private fun requestSummary() {
        val ctx = requireContext()
        if (GeminiSummarizer.hasConsent(ctx)) {
            vm.summarize(ctx)
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ai_summary_consent_title)
            .setMessage(R.string.ai_summary_consent_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_summary_consent_accept) { _, _ ->
                GeminiSummarizer.grantConsent(ctx)
                vm.summarize(ctx)
            }
            .show()
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

        if (aiMode && hasText && state.summary == null && !state.isSummarizing && state.summaryError == null &&
            autoSummarizedUri != state.sourceUri
        ) {
            autoSummarizedUri = state.sourceUri
            requestSummary()
        }
        body.summarize.isVisible = hasText && GeminiSummarizer.isAvailable(requireContext())
        ToolUi.setBusy(
            body.summarize, state.isSummarizing, R.drawable.ic_auto_awesome,
            getString(if (state.isSummarizing) R.string.ai_summary_working else R.string.ai_summary_action)
        )
        body.summaryCard.isVisible = state.summary != null
        if (body.summaryText.text.toString() != state.summary.orEmpty()) body.summaryText.text = state.summary
        body.summaryNote.setText(if (state.summaryTruncated) R.string.ai_summary_truncated_note else R.string.ai_summary_note)
        body.summaryError.text = state.summaryError
        body.summaryError.isVisible = state.summaryError != null

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
            aiMode -> getString(R.string.ai_summary_pick_hint)
            else -> getString(R.string.extract_pick_hint)
        }
    }

    private companion object {
        const val ARG_AI_MODE = "ai_mode"
    }
}
