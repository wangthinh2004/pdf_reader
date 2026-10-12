package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ToolImagesToPdfBinding
import com.chethan616.clearpdf.ui.common.SaveDialog
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.viewmodel.ImagesToPdfUiState
import com.chethan616.clearpdf.ui.viewmodel.ImagesToPdfViewModel

class ImagesToPdfFragment : ToolFragment<ToolImagesToPdfBinding>() {

    override val titleRes = R.string.images_to_pdf_title

    private val vm: ImagesToPdfViewModel by viewModels {
        viewModelFactory { ImagesToPdfViewModel(PdfServiceLocator.pdfConverter) }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.onPickImages(uris)
    }
    private val saveDialog = SaveDialog(this)
    private lateinit var grid: ImageGridAdapter

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolImagesToPdfBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val accent = requireContext().color(R.color.tool_images_to_pdf)
        ToolUi.primaryButton(body.add, accent)
        body.add.setOnClickListener { picker.launch("image/*") }
        body.clear.setOnClickListener { vm.clearImages() }

        body.pageSize.first.setText(R.string.images_fit_a4)
        body.pageSize.second.setText(R.string.images_original_size)
        ToolUi.segment(body.pageSize.first, accent)
        ToolUi.segment(body.pageSize.second, accent)
        body.pageSize.group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) vm.setFitToA4(id == body.pageSize.first.id)
        }

        grid = ImageGridAdapter(vm::removeImage)
        body.grid.layoutManager = GridLayoutManager(requireContext(), 3)
        body.grid.adapter = grid
        body.grid.isNestedScrollingEnabled = false

        ToolUi.primaryButton(body.create, accent)
        body.create.setOnClickListener {
            val state = vm.uiState.value
            if (!state.isSaving && state.imageUris.isNotEmpty()) {
                saveDialog.show("ClearPDF_Images") { name, folder -> vm.save(requireContext(), name, folder) }
            }
        }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun render(state: ImagesToPdfUiState) {
        val accent = requireContext().color(R.color.tool_images_to_pdf)
        val hasImages = state.imageUris.isNotEmpty()
        body.clear.isVisible = hasImages
        body.hint.isVisible = !hasImages
        body.grid.isVisible = hasImages
        grid.submit(state.imageUris)
        val checkedId = if (state.fitToA4) body.pageSize.first.id else body.pageSize.second.id
        if (body.pageSize.group.checkedButtonId != checkedId) body.pageSize.group.check(checkedId)

        ToolUi.setEnabled(body.create, hasImages || state.isSaving)
        ToolUi.setBusy(
            body.create, state.isSaving, R.drawable.ic_picture_as_pdf,
            if (state.isSaving) getString(R.string.images_to_pdf_working) else "${getString(R.string.tool_create)} (${state.imageUris.size})"
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
    }
}
