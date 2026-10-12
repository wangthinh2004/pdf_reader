package com.chethan616.clearpdf.ui.fragment.scan

import android.app.Activity
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.model.ScanFilter
import com.chethan616.clearpdf.data.model.ScanPage
import com.chethan616.clearpdf.data.repository.GitHubStarPromptManager
import com.chethan616.clearpdf.databinding.FragmentScanBinding
import com.chethan616.clearpdf.databinding.ItemScanPageBinding
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import com.chethan616.clearpdf.ui.utils.StarPromptEventBus
import com.chethan616.clearpdf.ui.viewmodel.ScanUiState
import com.chethan616.clearpdf.ui.viewmodel.ScanViewModel
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch

/** Scan pages with the ML Kit document scanner (or import photos), pick a filter, save as PDF. */
class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private val vm: ScanViewModel by viewModels()
    private lateinit var adapter: PagesAdapter
    private val filterChips = mutableMapOf<Int, ScanFilter>()

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.mapNotNull { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) vm.onScanComplete(uris, ScanFilter.ORIGINAL)
        }
        vm.cancelScanning()
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.onScanComplete(uris, ScanFilter.AUTO)
        vm.cancelScanning()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_scan)
        binding.root.applySystemBarPadding(top = true, bottom = true)
        binding.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        binding.scanIconTile.tintBackground(ctx.accentWash(accent))
        binding.scan.setOnClickListener { launchScanner() }
        binding.gallery.setOnClickListener { pickFromGallery() }
        binding.scanMore.setOnClickListener { launchScanner() }
        binding.addGallery.setOnClickListener { pickFromGallery() }
        binding.clearAll.setOnClickListener { vm.clearAllPages() }

        listOf(
            R.drawable.ic_crop_free to R.string.scan_feature_edges,
            R.drawable.ic_camera_alt to R.string.scan_feature_perspective,
            R.drawable.ic_document_scanner to R.string.scan_feature_multi_page,
            R.drawable.ic_photo_library to R.string.scan_feature_gallery,
            R.drawable.ic_picture_as_pdf to R.string.scan_feature_export_pdf
        ).forEach { (icon, label) ->
            binding.features.addView(TextView(ctx).apply {
                setText(label)
                textSize = 14f
                setTextColor(ctx.color(R.color.app_text))
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = ctx.dp(12)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(12) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
        }

        filterChips.clear()
        listOf(
            ScanFilter.ORIGINAL to R.string.scan_original,
            ScanFilter.AUTO to R.string.scan_auto,
            ScanFilter.GRAYSCALE to R.string.scan_grayscale,
            ScanFilter.BLACK_WHITE to R.string.scan_black_white,
            ScanFilter.COLOR to R.string.scan_vivid
        ).forEach { (filter, label) ->
            val chip = (layoutInflater.inflate(R.layout.view_filter_chip, binding.filters, false) as Chip).apply {
                id = View.generateViewId()
                setText(label)
                chipBackgroundColor = android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accent, ctx.accentWash(accent, 0.12f, 0.2f))
                )
            }
            binding.filters.addView(chip)
            filterChips[chip.id] = filter
        }
        binding.filters.setOnCheckedStateChangeListener { _, ids ->
            val filter = ids.firstOrNull()?.let(filterChips::get) ?: return@setOnCheckedStateChangeListener
            val pages = vm.uiState.value.scannedPages
            if (pages.any { it.filter != filter }) pages.indices.forEach { vm.applyFilter(it, filter) }
        }

        adapter = PagesAdapter()
        binding.pages.layoutManager = GridLayoutManager(ctx, 2)
        binding.pages.adapter = adapter
        binding.save.setOnClickListener { save() }
        collectWhileStarted(vm.uiState, ::render)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun launchScanner() {
        val activity = activity ?: return
        vm.startScanning()
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                vm.setError(getString(R.string.scanner_start_failed))
                vm.cancelScanning()
            }
    }

    private fun pickFromGallery() {
        vm.startScanning()
        galleryLauncher.launch("image/*")
    }

    private fun save() {
        val state = vm.uiState.value
        if (state.isScanning || state.scannedPages.isEmpty()) return
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            vm.startScanning() // doubles as the "saving" indicator
            val filter = state.scannedPages.first().filter
            val uri = ScanPdfExporter.save(ctx, state.scannedPages.map { it.uri }, filter)
            vm.cancelScanning()
            if (uri != null) {
                vm.markAsSaved(uri)
                if (GitHubStarPromptManager.recordPdfInteraction(ctx)) StarPromptEventBus.requestPrompt()
                Toast.makeText(ctx, R.string.pdf_saved_successfully, Toast.LENGTH_SHORT).show()
            } else {
                vm.setError(getString(R.string.pdf_save_failed))
            }
        }
    }

    private fun render(state: ScanUiState) {
        val hasPages = state.scannedPages.isNotEmpty()
        binding.empty.isVisible = !hasPages
        binding.pagesGroup.isVisible = hasPages
        ToolUi.status(binding.error, state.error, isError = true, onDismiss = { vm.setError(null) })
        binding.pageCount.text = if (state.scannedPages.size == 1) getString(R.string.scan_page_scanned)
        else getString(R.string.scan_pages_scanned, state.scannedPages.size)
        adapter.submit(state.scannedPages)
        val current = state.scannedPages.firstOrNull()?.filter ?: ScanFilter.ORIGINAL
        filterChips.entries.firstOrNull { it.value == current }?.key?.let { id ->
            if (binding.filters.checkedChipId != id) binding.filters.check(id)
        }
        ToolUi.setBusy(binding.save, state.isScanning && hasPages, R.drawable.ic_picture_as_pdf,
            getString(if (state.isScanning && hasPages) R.string.saving else R.string.save_as_pdf))
    }

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.Holder>() {
        private var pages: List<ScanPage> = emptyList()

        fun submit(pages: List<ScanPage>) {
            if (pages == this.pages) return
            this.pages = pages
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemScanPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

        inner class Holder(private val b: ItemScanPageBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.remove.setOnClickListener { bindingAdapterPosition.takeIf { it >= 0 }?.let(vm::deletePage) }
            }

            fun bind(index: Int) {
                val page = pages[index]
                b.image.load(page.uri)
                b.image.colorFilter = ScanPdfExporter.matrix(page.filter)?.let { ColorMatrixColorFilter(it) }
                b.image.contentDescription = b.root.context.getString(R.string.page_number, index + 1)
                b.number.text = "${index + 1}"
            }
        }
    }
}
