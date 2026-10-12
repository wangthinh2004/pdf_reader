package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolHtmlToPdfBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.viewmodel.HtmlToPdfUiState
import com.chethan616.clearpdf.ui.viewmodel.HtmlToPdfViewModel
import com.chethan616.clearpdf.ui.viewmodel.WebToPdfMode

class HtmlToPdfFragment : ToolFragment<ToolHtmlToPdfBinding>() {

    override val titleRes = R.string.tool_html_to_pdf

    private val vm: HtmlToPdfViewModel by viewModels()

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.onLoadFile(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolHtmlToPdfBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_html_to_pdf)
        body.header.icon.setImageResource(R.drawable.ic_code)
        body.header.icon.tint(accent)
        body.header.title.setText(R.string.tool_html_to_pdf)
        body.mode.first.setText(R.string.html_to_pdf_mode_web)
        body.mode.second.setText(R.string.html_to_pdf_mode_html)
        ToolUi.segment(body.mode.first, accent)
        ToolUi.segment(body.mode.second, accent)
        body.mode.group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) vm.onModeChange(if (id == body.mode.second.id) WebToPdfMode.HTML else WebToPdfMode.URL)
        }
        body.urlLayout.hint = getString(R.string.html_to_pdf_url_hint)
        body.url.doAfterTextChanged { vm.onUrlChange(it?.toString().orEmpty()) }
        body.htmlLayout.hint = getString(R.string.html_to_pdf_hint)
        body.html.doAfterTextChanged { vm.onHtmlChange(it?.toString().orEmpty()) }
        ToolUi.primaryButton(body.loadFile, accent)
        body.loadFile.setOnClickListener { filePicker.launch("text/html") }
        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.convert(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: HtmlToPdfUiState) {
        val accent = requireContext().color(R.color.tool_html_to_pdf)
        val html = state.mode == WebToPdfMode.HTML
        val checkedId = if (html) body.mode.second.id else body.mode.first.id
        if (body.mode.group.checkedButtonId != checkedId) body.mode.group.check(checkedId)
        body.urlGroup.isVisible = !html
        body.htmlGroup.isVisible = html
        ToolUi.syncText(body.url, state.url)
        ToolUi.syncText(body.html, state.html)
        body.sourceName.text = state.sourceName
        body.sourceName.isVisible = state.sourceName.isNotEmpty()
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_picture_as_pdf,
            getString(if (state.isProcessing) R.string.html_to_pdf_working else R.string.html_to_pdf_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
