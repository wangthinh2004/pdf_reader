package com.chethan616.clearpdf.ui.fragment.tool

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.databinding.FragmentOrganizerBinding
import com.chethan616.clearpdf.databinding.ItemOrganizerPageBinding
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.SaveDialog
import com.chethan616.clearpdf.ui.common.UnsavedChanges
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.viewmodel.OrganizerPage
import com.chethan616.clearpdf.ui.viewmodel.PageOrganizerUiState
import com.chethan616.clearpdf.ui.viewmodel.PageOrganizerViewModel

/** Reorder, rotate and delete pages. Long-press or drag the handle to move a page (or the selection). */
class PageOrganizerFragment : Fragment() {

    private var _binding: FragmentOrganizerBinding? = null
    private val binding get() = _binding!!

    private val vm: PageOrganizerViewModel by viewModels {
        viewModelFactory { PageOrganizerViewModel(PdfServiceLocator.pdfEditor) }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSelectFile(requireContext(), uri)
    }
    private val saveDialog = SaveDialog(this)
    private lateinit var adapter: PagesAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private val backGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = confirmExit()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOrganizerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        val accent = ctx.color(R.color.tool_organize)
        binding.root.applySystemBarPadding(top = true, bottom = true)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        binding.toolbar.setNavigationOnClickListener { requestBack() }

        ToolUi.hero(
            binding.hero, R.drawable.ic_reorder, accent,
            getString(R.string.organize_title), getString(R.string.tool_organizer_description),
            getString(R.string.viewer_pick_pdf)
        ) { picker.launch(arrayOf("application/pdf")) }

        binding.rotateSelected.setOnClickListener { vm.rotateSelected() }
        binding.deleteSelected.setOnClickListener { vm.deleteSelected(requireContext()) }
        binding.selectToggle.setOnClickListener {
            if (vm.uiState.value.selectedIds.isEmpty()) vm.selectAll() else vm.clearSelection()
        }

        adapter = PagesAdapter()
        binding.pages.layoutManager = LinearLayoutManager(ctx)
        binding.pages.adapter = adapter
        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.pages)

        ToolUi.primaryButton(binding.save, accent)
        binding.save.setOnClickListener { if (!vm.uiState.value.isSaving) showSave(leaveAfter = false) }
        collectWhileStarted(vm.uiState, ::render)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun requestBack() {
        val s = vm.uiState.value
        if (s.dirty && !s.isSaving) confirmExit() else findNavController().popBackStack()
    }

    private fun confirmExit() {
        UnsavedChanges.confirm(
            requireContext(),
            onSave = { showSave(leaveAfter = true) },
            onDiscard = { findNavController().popBackStack() }
        )
    }

    private fun showSave(leaveAfter: Boolean) {
        val name = vm.uiState.value.sourceFileName.substringBeforeLast('.').ifBlank { "Document" } + "_Organized"
        saveDialog.show(name) { fileName, folder ->
            vm.save(requireContext(), fileName, folder, onSaved = if (leaveAfter) ({ findNavController().popBackStack() }) else null)
        }
    }

    private fun render(state: PageOrganizerUiState) {
        val accent = requireContext().color(R.color.tool_organize)
        backGuard.isEnabled = state.dirty && !state.isSaving
        val loaded = state.sourceUri != null && !state.isLoading
        binding.intro.isVisible = state.sourceUri == null
        binding.introError.text = state.errorMessage
        binding.introError.isVisible = state.sourceUri == null && state.errorMessage != null
        binding.loading.isVisible = state.sourceUri != null && state.isLoading
        binding.editor.isVisible = loaded
        if (!loaded) return

        val sel = state.selectedIds.size
        binding.selection.text = if (sel > 0) "$sel ${getString(R.string.select_numeral)}"
        else "${state.pages.size} ${getString(R.string.page_numeral)}${getString(R.string.count_plural_f)}"
        binding.rotateSelected.isVisible = sel > 0
        binding.deleteSelected.isVisible = sel > 0
        binding.selectToggle.setText(if (sel > 0) R.string.viewer_clear else R.string.select_all)
        adapter.submit(state.pages, state.selectedIds)

        ToolUi.setBusy(binding.save, state.isSaving, R.drawable.ic_save,
            getString(if (state.isSaving) R.string.saving else R.string.save_as_new_pdf))
        ToolUi.openOutput(binding.openOutput.root, state.lastOutputUri, accent) {
            AppNavigator.openPdfViewer(findNavController(), it)
        }
        val message = state.errorMessage ?: state.resultMessage
        ToolUi.status(binding.status, message, isError = state.errorMessage != null, onDismiss = vm::clearFeedback)
    }

    // ── List ──────────────────────────────────────────────────────────────────────────────────

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.Holder>() {
        var pages: MutableList<OrganizerPage> = mutableListOf()
        private var selected: Set<Int> = emptySet()
        var dragging = false

        fun submit(pages: List<OrganizerPage>, selected: Set<Int>) {
            if (dragging) return
            this.pages = pages.toMutableList()
            this.selected = selected
            notifyDataSetChanged()
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemOrganizerPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

        inner class Holder(private val b: ItemOrganizerPageBinding) : RecyclerView.ViewHolder(b.root) {
            init {
                b.card.setOnClickListener { pageAt()?.let { vm.toggleSelect(it.originalIndex) } }
                b.rotate.setOnClickListener { bindingAdapterPosition.takeIf { it >= 0 }?.let { vm.rotatePage(it) } }
                b.delete.setOnClickListener { bindingAdapterPosition.takeIf { it >= 0 }?.let { vm.deletePage(it) } }
                b.handle.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this)
                    v.performClick()
                    false
                }
            }

            private fun pageAt() = pages.getOrNull(bindingAdapterPosition)

            fun bind(index: Int) {
                val ctx = b.root.context
                val page = pages[index]
                val isSelected = page.originalIndex in selected
                val accent = ctx.color(R.color.tool_organize)
                b.card.strokeWidth = if (isSelected) ctx.dp(2) else 0
                b.card.strokeColor = accent
                b.check.setImageResource(if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_radio_button_unchecked)
                b.check.tint(if (isSelected) accent else ctx.color(R.color.app_text_secondary))
                b.thumb.setImageBitmap(page.thumbnail)
                b.thumb.rotation = page.rotation.toFloat()
                b.thumb.contentDescription = ctx.getString(R.string.page_number, index + 1)
                b.label.text = ctx.getString(R.string.create_page_label, index + 1)
                b.source.text = "${ctx.getString(R.string.source)} #${page.originalIndex + 1}" +
                    if (page.rotation != 0) " · ${page.rotation}°" else ""
            }
        }
    }

    /**
     * Drag to reorder. The list moves locally while the finger is down; on release the move is
     * committed to the view model — the whole selection travels when the dragged page is part of it.
     */
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        private var fromIndex = -1
        private var toIndex = -1
        private var draggedId = -1

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                adapter.dragging = true
                fromIndex = viewHolder.bindingAdapterPosition
                toIndex = fromIndex
                draggedId = adapter.pages.getOrNull(fromIndex)?.originalIndex ?: -1
                viewHolder.itemView.alpha = 0.85f
            }
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            adapter.pages.add(to, adapter.pages.removeAt(from))
            adapter.notifyItemMoved(from, to)
            toIndex = to
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            adapter.dragging = false
            if (draggedId >= 0 && fromIndex >= 0 && toIndex != fromIndex) {
                val selected = vm.uiState.value.selectedIds
                val ids = if (draggedId in selected && selected.size > 1) {
                    vm.uiState.value.pages.filter { it.originalIndex in selected }.map { it.originalIndex }
                } else listOf(draggedId)
                // movePagesTo takes an insertion index in the list as it was before the move.
                vm.movePagesTo(ids, if (toIndex > fromIndex) toIndex + 1 else toIndex)
            } else {
                render(vm.uiState.value)
            }
            draggedId = -1
        }
    }
}
