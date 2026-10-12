package com.chethan616.clearpdf.ui.fragment.tool

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ToolPdfToImagesBinding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.PdfToImagesUiState
import com.chethan616.clearpdf.ui.viewmodel.PdfToImagesViewModel
import com.google.android.material.button.MaterialButton
import com.kyant.pdfcore.raster.PdfRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfToImagesFragment : ToolFragment<ToolPdfToImagesBinding>() {

    override val titleRes = R.string.tool_pdf_to_images

    private val vm: PdfToImagesViewModel by viewModels()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }

    private lateinit var preview: PageGridAdapter
    private val formatButtons = mutableMapOf<Int, PdfRasterizer.ImageFormat>()
    private var previewPages: List<PdfRasterizer.RasterPage> = emptyList()

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolPdfToImagesBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_pdf_to_images)
        ToolUi.hero(
            body.hero, R.drawable.ic_image, accent,
            getString(R.string.tool_pdf_to_images), getString(R.string.tool_pdf_to_images_desc),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }

        formatButtons.clear()
        PdfRasterizer.ImageFormat.entries.forEach { fmt ->
            val button = layoutInflater.inflate(R.layout.view_segment_button, body.formats, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = fmt.extension.uppercase()
            ToolUi.segment(button, accent)
            body.formats.addView(button)
            formatButtons[button.id] = fmt
        }
        body.formats.addOnButtonCheckedListener { _, id, checked ->
            if (checked) formatButtons[id]?.let(vm::onFormatChange)
        }

        // Preview tiles only show the page number; tapping does nothing.
        preview = PageGridAdapter(accent) { }
        PageGridAdapter.attach(body.preview, preview)

        ToolUi.primaryButton(body.run, accent)
        body.run.setOnClickListener { vm.run(requireContext()) }
        body.save.setTextColor(android.graphics.Color.WHITE)
        body.save.setOnClickListener { vm.saveToGallery(requireContext()) }
        body.share.setOnClickListener { vm.shareAll(requireContext()) }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: PdfToImagesUiState) {
        val accent = requireContext().color(R.color.tool_pdf_to_images)
        val hasFile = state.sourceName.isNotEmpty()
        ToolUi.file(body.file, state.sourceName, getString(R.string.create_pages, state.pageCount), accent)
        body.options.isVisible = hasFile
        body.run.isVisible = hasFile
        val checked = formatButtons.entries.firstOrNull { it.value == state.format }?.key
        if (checked != null && body.formats.checkedButtonId != checked) body.formats.check(checked)
        ToolUi.setBusy(
            body.run, state.isProcessing, R.drawable.ic_image,
            if (state.isProcessing) getString(R.string.pdf_to_images_working, (state.progress * 100).toInt())
            else getString(R.string.pdf_to_images_convert)
        )

        body.results.isVisible = state.resultPages.isNotEmpty()
        if (state.resultPages !== previewPages) {
            previewPages = state.resultPages
            loadPreview(state.resultPages.take(12))
        }

        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }

    /** Decodes small previews of the first rendered pages off the main thread. */
    private fun loadPreview(pages: List<PdfRasterizer.RasterPage>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val thumbs: List<Bitmap?> = withContext(Dispatchers.IO) {
                pages.map { page ->
                    runCatching {
                        BitmapFactory.decodeFile(page.file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }.getOrNull()
                }
            }
            preview.submit(pages.size, thumbs, emptySet())
        }
    }
}
