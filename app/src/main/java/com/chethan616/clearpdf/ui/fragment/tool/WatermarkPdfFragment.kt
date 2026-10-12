package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolWatermarkBinding
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.viewmodel.WatermarkMode
import com.chethan616.clearpdf.ui.viewmodel.WatermarkPdfViewModel
import com.chethan616.clearpdf.ui.viewmodel.WatermarkUiState

class WatermarkPdfFragment : ToolFragment<ToolWatermarkBinding>() {

    override val titleRes = R.string.tool_watermark

    private val vm: WatermarkPdfViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }
    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.onPickImage(requireContext(), uri)
    }

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolWatermarkBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_watermark)
        ToolUi.hero(
            body.hero, R.drawable.ic_branding_watermark, accent,
            getString(R.string.tool_watermark), getString(R.string.tool_watermark_sub),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }
        body.header.icon.setImageResource(R.drawable.ic_branding_watermark)
        body.header.icon.tint(accent)
        body.header.title.setText(R.string.watermark_text_label)

        body.mode.first.setText(R.string.watermark_mode_text)
        body.mode.second.setText(R.string.watermark_mode_image)
        ToolUi.segment(body.mode.first, accent)
        ToolUi.segment(body.mode.second, accent)
        body.mode.group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) vm.onModeChange(if (id == body.mode.second.id) WatermarkMode.IMAGE else WatermarkMode.TEXT)
        }
        body.textLayout.hint = getString(R.string.watermark_text_hint)
        body.text.doAfterTextChanged { vm.onTextChange(it?.toString().orEmpty()) }
        ToolUi.primaryButton(body.pickImage, accent)
        body.pickImage.setOnClickListener { imagePicker.launch("image/*") }
        body.opacityValue.setTextColor(accent)
        body.opacityValue.tintBackground(ctx.accentWash(accent))
        body.opacity.addOnChangeListener { _, value, fromUser -> if (fromUser) vm.onOpacityChange(value) }
        body.diagonal.setOnCheckedChangeListener { _, value -> vm.onDiagonalChange(value) }
        body.diagonalRow.setOnClickListener { body.diagonal.toggle() }

        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.apply(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: WatermarkUiState) {
        val accent = requireContext().color(R.color.tool_watermark)
        val hasFile = state.sourceUri != null
        ToolUi.file(body.file, state.sourceName.takeIf { hasFile }, null, accent)
        body.options.isVisible = hasFile
        body.run.isVisible = hasFile

        val image = state.mode == WatermarkMode.IMAGE
        val checkedId = if (image) body.mode.second.id else body.mode.first.id
        if (body.mode.group.checkedButtonId != checkedId) body.mode.group.check(checkedId)
        body.textLayout.isVisible = !image
        body.imageGroup.isVisible = image
        ToolUi.syncText(body.text, state.text)
        body.imageName.text = state.imageName
        body.imageName.isVisible = state.imageName.isNotEmpty()
        val opacity = state.opacity.coerceIn(0.05f, 1f)
        if (body.opacity.value != opacity) body.opacity.value = opacity
        body.opacityValue.text = "${(state.opacity * 100).toInt()}%"
        if (body.diagonal.isChecked != state.diagonal) body.diagonal.isChecked = state.diagonal

        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_branding_watermark,
            getString(if (state.isProcessing) R.string.watermark_applying else R.string.watermark_apply)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }
}
