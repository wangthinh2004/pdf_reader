package com.chethan616.clearpdf.presentation.feature.tool.extracttext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toDocumentRef
import com.chethan616.clearpdf.databinding.ToolExtractTextBinding
import com.chethan616.clearpdf.presentation.base.BaseToolFragment
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExtractTextFragment : BaseToolFragment<ToolExtractTextBinding>(ToolExtractTextBinding::inflate) {

    private val viewModel: ExtractTextViewModel by viewModels()

    private val aiMode get() = arguments?.getBoolean(ARG_AI_MODE) == true

    override val titleRes get() = if (aiMode) R.string.tool_ai_summary else R.string.tool_extract

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFilePicked(uri.toDocumentRef())
    }

    override fun initViews(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_extract_text)
        ToolUi.primaryButton(body.pick, accent)
        ToolUi.primaryButton(body.recognize, accent)
        ToolUi.primaryButton(body.summarize, accent)
        ToolUi.primaryButton(body.searchable, requireContext().color(R.color.app_success))
    }

    override fun initListeners() {
        body.pick.setOnClickListener { picker.launch(arrayOf("application/pdf")) }
        body.copy.setOnClickListener { copyToClipboard(getString(R.string.tool_extract), viewModel.state.value.text) }
        body.share.setOnClickListener { shareText(viewModel.state.value.text) }
        body.recognize.setOnClickListener { viewModel.onRecognizeClicked() }
        body.searchable.setOnClickListener { viewModel.onMakeSearchableClicked() }
        body.summarize.setOnClickListener { viewModel.onSummarizeClicked() }
        body.summaryCopy.setOnClickListener {
            copyToClipboard(getString(R.string.ai_summary_title), viewModel.state.value.summary?.text.orEmpty())
        }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted(::render)
        viewModel.events.collectOnStarted { event ->
            when (event) {
                ExtractTextUiEvent.ShowSummaryConsent -> showConsentDialog()
            }
        }
    }

    private fun showConsentDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_summary_consent_title)
            .setMessage(R.string.ai_summary_consent_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ai_summary_consent_accept) { _, _ -> viewModel.onSummaryConsentGranted() }
            .show()
    }

    private fun render(state: ExtractTextUiState) {
        val ctx = requireContext()
        val hasText = state.text.isNotEmpty()
        body.copy.isVisible = hasText
        body.share.isVisible = hasText
        body.sourceName.text = state.source?.name
        body.sourceName.isVisible = state.source != null
        body.error.text = state.error?.resolve(ctx)
        body.error.isVisible = state.error != null
        body.recognize.isVisible = state.canRecognize && !state.isRecognizing
        body.searchable.isVisible = state.canMakeSearchable
        ToolUi.setBusy(body.searchable, state.isSavingSearchable, null, getString(R.string.extract_make_searchable))
        body.searchableSaved.isVisible = state.searchableOutput != null
        body.searchableSaved.text = getString(R.string.extract_searchable_saved, state.searchableOutput?.locationLabel.orEmpty())

        body.summarize.isVisible = hasText && state.summaryAvailable
        ToolUi.setBusy(
            body.summarize, state.isSummarizing, R.drawable.ic_auto_awesome,
            getString(if (state.isSummarizing) R.string.ai_summary_working else R.string.ai_summary_action)
        )
        body.summaryCard.isVisible = state.summary != null
        val summaryText = state.summary?.text.orEmpty()
        if (body.summaryText.text.toString() != summaryText) body.summaryText.text = summaryText
        body.summaryNote.setText(if (state.summary?.truncated == true) R.string.ai_summary_truncated_note else R.string.ai_summary_note)
        body.summaryError.text = state.summaryError?.resolve(ctx)
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
            state.aiMode -> getString(R.string.ai_summary_pick_hint)
            else -> getString(R.string.extract_pick_hint)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, getString(R.string.extract_share_text))) }
    }
}
