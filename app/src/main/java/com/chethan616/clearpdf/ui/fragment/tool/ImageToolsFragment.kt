package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolImageToolsBinding
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.viewmodel.ImageToolsUiState
import com.chethan616.clearpdf.ui.viewmodel.ImageToolsViewModel
import com.google.android.material.button.MaterialButton
import com.kyant.pdfcore.raster.PdfRasterizer.ImageFormat

class ImageToolsFragment : ToolFragment<ToolImageToolsBinding>() {

    override val titleRes = R.string.tool_image_tools

    private val vm: ImageToolsViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.onSelectImage(requireContext(), uri)
    }

    private val formatButtons = mutableMapOf<Int, ImageFormat>()

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolImageToolsBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_image_tools)
        ToolUi.hero(
            body.hero, R.drawable.ic_image, accent,
            getString(R.string.tool_image_tools), getString(R.string.tool_image_tools_sub),
            getString(R.string.image_tools_pick)
        ) { picker.launch("image/*") }
        body.header.icon.setImageResource(R.drawable.ic_tune)
        body.header.icon.tint(accent)
        body.header.title.setText(R.string.image_tools_options)

        formatButtons.clear()
        listOf(ImageFormat.JPEG to "JPG", ImageFormat.PNG to "PNG", ImageFormat.WEBP to "WebP").forEach { (fmt, label) ->
            val button = layoutInflater.inflate(R.layout.view_segment_button, body.formats, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = label
            ToolUi.segment(button, accent)
            body.formats.addView(button)
            formatButtons[button.id] = fmt
        }
        body.formats.addOnButtonCheckedListener { _, id, checked -> if (checked) formatButtons[id]?.let(vm::onFormatChange) }

        listOf(body.qualityValue, body.scaleValue).forEach {
            it.setTextColor(accent)
            it.tintBackground(ctx.accentWash(accent))
        }
        body.quality.addOnChangeListener { _, value, fromUser -> if (fromUser) vm.onQualityChange(value.toInt()) }
        body.scale.addOnChangeListener { _, value, fromUser -> if (fromUser) vm.onScaleChange(value.toInt()) }

        ToolUi.primaryButton(body.run, accent)
        ToolUi.primaryButton(body.save, accent)
        ToolUi.primaryButton(body.share, accent)
        body.run.setOnClickListener { vm.process(requireContext()) }
        body.save.setOnClickListener { vm.saveToGallery(requireContext()) }
        body.share.setOnClickListener { vm.share(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: ImageToolsUiState) {
        val hasImage = state.sourceUri != null
        body.options.isVisible = hasImage
        body.run.isVisible = hasImage
        body.sourceInfo.text = "${state.sourceName}  ·  ${state.srcWidth}×${state.srcHeight}  ·  ${state.srcSizeBytes / 1024} KB"
        val checked = formatButtons.entries.firstOrNull { it.value == state.format }?.key
        if (checked != null && body.formats.checkedButtonId != checked) body.formats.check(checked)
        body.qualityGroup.isVisible = state.format != ImageFormat.PNG
        state.quality.toFloat().coerceIn(30f, 100f).let { if (body.quality.value != it) body.quality.value = it }
        state.scalePercent.toFloat().coerceIn(10f, 100f).let { if (body.scale.value != it) body.scale.value = it }
        body.qualityValue.text = "${state.quality}%"
        body.scaleValue.text = "${state.scalePercent}%"
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_tune,
            getString(if (state.isProcessing) R.string.image_tools_processing else R.string.image_tools_process)
        )
        body.result.isVisible = state.result != null
        body.resultMessage.text = state.resultMessage
        body.resultMessage.isVisible = state.resultMessage != null
        ToolUi.status(body.status, state.errorMessage, isError = true, onDismiss = vm::clearFeedback)
        autoClear(state.errorMessage, vm::clearFeedback)
    }
}
