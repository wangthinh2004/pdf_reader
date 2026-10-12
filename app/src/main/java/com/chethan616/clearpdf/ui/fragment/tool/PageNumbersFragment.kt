package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolPageNumbersBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.fragment.settings.bindToggle
import com.chethan616.clearpdf.ui.viewmodel.PageNumbersUiState
import com.chethan616.clearpdf.ui.viewmodel.PageNumbersViewModel
import com.kyant.pdfcore.pagenumber.PdfPageNumberer

class PageNumbersFragment : ToolFragment<ToolPageNumbersBinding>() {

    override val titleRes = R.string.tool_page_numbers

    private val vm: PageNumbersViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolPageNumbersBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_page_numbers)
        ToolUi.hero(
            body.hero, R.drawable.ic_numbers, accent,
            getString(R.string.tool_page_numbers), getString(R.string.tool_page_numbers_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.header.icon.setImageResource(R.drawable.ic_numbers)
        body.header.icon.tint(accent)
        body.header.title.setText(R.string.page_numbers_position)

        body.position.first.setText(R.string.page_numbers_center)
        body.position.second.setText(R.string.page_numbers_right)
        ToolUi.segment(body.position.first, accent)
        ToolUi.segment(body.position.second, accent)
        body.position.group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) vm.onPositionChange(
                if (id == body.position.second.id) PdfPageNumberer.Position.RIGHT else PdfPageNumberer.Position.CENTER
            )
        }
        bindToggle(
            ctx, body.includeTotal, R.drawable.ic_numbers,
            getString(R.string.page_numbers_total), getString(R.string.page_numbers_total_desc),
            vm.uiState.value.includeTotal, vm::onIncludeTotalChange
        )

        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.apply(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: PageNumbersUiState) {
        val accent = requireContext().color(R.color.tool_page_numbers)
        val hasFile = state.sourceUri != null
        body.options.isVisible = hasFile
        body.run.isVisible = hasFile
        body.sourceName.text = state.sourceName
        val checkedId = if (state.position == PdfPageNumberer.Position.RIGHT) body.position.second.id else body.position.first.id
        if (body.position.group.checkedButtonId != checkedId) body.position.group.check(checkedId)
        if (body.includeTotal.toggle.isChecked != state.includeTotal) body.includeTotal.toggle.isChecked = state.includeTotal
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_numbers,
            getString(if (state.isProcessing) R.string.page_numbers_working else R.string.page_numbers_action)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
