package com.chethan616.clearpdf.ui.fragment.tool

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import coil3.load
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.ItemDraftPageBinding
import com.chethan616.clearpdf.databinding.ToolCreateBinding
import com.chethan616.clearpdf.domain.usecase.CreatePdfUseCase
import com.chethan616.clearpdf.ui.common.SaveDialog
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.viewmodel.CreateMode
import com.chethan616.clearpdf.ui.viewmodel.CreatePdfUiState
import com.chethan616.clearpdf.ui.viewmodel.CreatePdfViewModel
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

class CreatePdfFragment : ToolFragment<ToolCreateBinding>() {

    override val titleRes = R.string.tool_create

    private val vm: CreatePdfViewModel by viewModels {
        viewModelFactory { CreatePdfViewModel(CreatePdfUseCase(PdfServiceLocator.pdfCreator)) }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            vm.onModeSelected(CreateMode.FROM_IMAGES)
            vm.onImagesSelected(uris)
        }
    }

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.mapNotNull { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) {
                vm.onModeSelected(CreateMode.FROM_IMAGES)
                vm.onImagesSelected(uris)
            }
        }
    }

    private val saveDialog = SaveDialog(this)
    private val modeButtons = mutableMapOf<Int, CreateMode>()
    private var shownDraft: List<android.net.Uri>? = null

    override fun inflateBody(inflater: LayoutInflater, parent: ViewGroup) = ToolCreateBinding.inflate(inflater, parent, false)

    override fun onBodyCreated(savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_create)
        ToolUi.hero(
            body.hero, R.drawable.ic_note_add, accent,
            getString(R.string.create_pdf_subtitle), getString(R.string.create_hint), null, null
        )

        modeButtons.clear()
        listOf(
            Triple(CreateMode.FROM_IMAGES, R.string.create_mode_images, R.drawable.ic_image),
            Triple(CreateMode.BLANK, R.string.create_mode_blank, R.drawable.ic_description),
            Triple(CreateMode.ADVANCED_TEXT, R.string.create_mode_text, R.drawable.ic_edit)
        ).forEach { (mode, label, icon) ->
            val button = layoutInflater.inflate(R.layout.view_segment_button, body.modes, false) as MaterialButton
            button.id = View.generateViewId()
            button.setText(label)
            button.setIconResource(icon)
            button.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            ToolUi.segment(button, accent)
            body.modes.addView(button)
            modeButtons[button.id] = mode
        }
        body.modes.addOnButtonCheckedListener { _, id, checked -> if (checked) modeButtons[id]?.let(vm::onModeSelected) }

        ToolUi.primaryButton(body.scan, accent)
        body.scan.setOnClickListener { launchScanner() }
        body.addImages.setOnClickListener { imagePicker.launch("image/*") }
        body.fewer.setOnClickListener { vm.onBlankPageCountChanged(vm.uiState.value.blankPageCount - 1) }
        body.more.setOnClickListener { vm.onBlankPageCountChanged(vm.uiState.value.blankPageCount + 1) }
        body.more.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        body.textLayout.hint = getString(R.string.create_text_hint)
        body.text.doAfterTextChanged { vm.onTextChanged(it?.toString().orEmpty()) }

        ToolUi.primaryButton(body.create, accent)
        body.create.setOnClickListener {
            if (!vm.uiState.value.isCreating) {
                saveDialog.show("ClearPDF_Document.pdf") { name, folder -> vm.onCreate(requireContext(), name, folder) }
            }
        }
        collectWhileStarted(vm.uiState, ::render)
    }

    private fun launchScanner() {
        val activity = activity ?: return vm.setError(getString(R.string.scanner_unavailable))
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { vm.setError(getString(R.string.scanner_start_failed)) }
    }

    private fun render(state: CreatePdfUiState) {
        val accent = requireContext().color(R.color.tool_create)
        val checked = modeButtons.entries.firstOrNull { it.value == state.selectedMode }?.key
        if (checked != null && body.modes.checkedButtonId != checked) body.modes.check(checked)
        body.imagesGroup.isVisible = state.selectedMode == CreateMode.FROM_IMAGES
        body.blankGroup.isVisible = state.selectedMode == CreateMode.BLANK
        body.textGroup.isVisible = state.selectedMode == CreateMode.ADVANCED_TEXT

        val count = state.selectedImageUris.size
        body.draftCount.text = "$count page${if (count == 1) "" else "s"} in draft"
        body.imagesEmpty.isVisible = count == 0
        if (state.selectedImageUris != shownDraft) {
            shownDraft = state.selectedImageUris
            renderDraft(state.selectedImageUris)
        }
        body.blankCount.text = getString(R.string.create_pages, state.blankPageCount)
        ToolUi.syncText(body.text, state.textContent)
        body.charCount.text = getString(R.string.create_character_count, state.textContent.length)

        val label = when (state.selectedMode) {
            CreateMode.FROM_IMAGES -> R.string.create_from_draft
            CreateMode.BLANK -> R.string.create_blank_pdf
            CreateMode.ADVANCED_TEXT -> R.string.create_text_pdf
        }
        ToolUi.setBusy(
            body.create, state.isCreating, R.drawable.ic_note_add,
            getString(if (state.isCreating) R.string.images_to_pdf_working else label)
        )
        ToolUi.openOutput(body.openOutput.root, state.lastOutputUri, accent, ::viewOutput)
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(body.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
        autoClear(message, vm::clearFeedback)
    }

    private fun renderDraft(uris: List<android.net.Uri>) {
        body.draftPages.removeAllViews()
        uris.forEachIndexed { index, uri ->
            val row = ItemDraftPageBinding.inflate(layoutInflater, body.draftPages, true)
            row.image.load(uri)
            row.image.contentDescription = getString(R.string.page_number, index + 1)
            row.label.text = getString(R.string.create_page_label, index + 1)
            row.up.isEnabled = index > 0
            row.down.isEnabled = index < uris.lastIndex
            row.up.setOnClickListener { vm.moveImage(index, index - 1) }
            row.down.setOnClickListener { vm.moveImage(index, index + 1) }
            row.remove.setOnClickListener { vm.removeImage(index) }
        }
        body.draftPages.setPadding(0, 0, 0, if (uris.isEmpty()) 0 else requireContext().dp(4))
    }
}
