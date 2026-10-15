package com.chethan616.clearpdf.presentation.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.SheetCreateBinding
import com.chethan616.clearpdf.databinding.ViewSheetActionBinding
import com.chethan616.clearpdf.databinding.ViewToolCardBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** What the user picked in the "Create PDF" sheet. The host acts on it after the sheet closes. */
enum class CreateAction { IMPORT_FILES, SCAN, IMAGES, PDF_TO_IMAGE, IMAGE_TO_PDF, PDF_TO_WORD, WORD_TO_PDF }

/**
 * "Create PDF" sheet. It only reports the choice through a fragment result ([REQUEST_KEY]);
 * launching pickers or tools is the host's job since the sheet is gone by then.
 */
class CreateSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCreateBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun getTheme() = R.style.ThemeOverlay_DS_BottomSheet

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetCreateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
        binding.createImport.setOnClickListener { pick(CreateAction.IMPORT_FILES) }
        binding.createScan.setOnClickListener { pick(CreateAction.SCAN) }
        binding.importFiles.setup(R.drawable.ic_upload_file, R.string.create_files, CreateAction.IMPORT_FILES)
        binding.importImages.setup(R.drawable.ic_photo_library, R.string.create_images, CreateAction.IMAGES)
        binding.importScan.setup(R.drawable.ic_document_scanner, R.string.create_scan, CreateAction.SCAN)
        binding.convertPdfToImage.setup(R.drawable.ic_ds_scissors, R.string.create_pdf_to_image, R.string.create_pdf_to_image_sub, CreateAction.PDF_TO_IMAGE)
        binding.convertImageToPdf.setup(R.drawable.ic_ds_combine, R.string.create_image_to_pdf, R.string.create_image_to_pdf_sub, CreateAction.IMAGE_TO_PDF)
        binding.convertPdfToWord.setup(R.drawable.ic_ds_file_word, R.string.create_pdf_to_word, R.string.create_pdf_to_word_sub, CreateAction.PDF_TO_WORD)
        binding.convertWordToPdf.setup(R.drawable.ic_ds_combine, R.string.create_word_to_pdf, R.string.create_word_to_pdf_sub, CreateAction.WORD_TO_PDF)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun ViewSheetActionBinding.setup(icon: Int, label: Int, action: CreateAction) {
        this.icon.setImageResource(icon)
        this.label.setText(label)
        root.setOnClickListener { pick(action) }
    }

    private fun ViewToolCardBinding.setup(icon: Int, title: Int, subtitle: Int, action: CreateAction) {
        this.icon.setImageResource(icon)
        this.title.setText(title)
        this.subtitle.setText(subtitle)
        root.setOnClickListener { pick(action) }
    }

    private fun pick(action: CreateAction) {
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_ACTION to action.name))
        dismiss()
    }

    companion object {
        const val TAG = "create_sheet"
        const val REQUEST_KEY = "create_sheet_result"
        private const val KEY_ACTION = "action"

        fun actionFrom(result: Bundle): CreateAction? =
            result.getString(KEY_ACTION)?.let { name -> CreateAction.entries.firstOrNull { it.name == name } }
    }
}
