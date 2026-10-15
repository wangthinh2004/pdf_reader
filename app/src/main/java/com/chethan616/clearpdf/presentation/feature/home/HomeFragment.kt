package com.chethan616.clearpdf.presentation.feature.home

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.StorageAccess
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.FragmentHomeScreenBinding
import com.chethan616.clearpdf.databinding.ViewHomeEmptyBinding
import com.chethan616.clearpdf.domain.model.DocumentSort
import com.chethan616.clearpdf.domain.model.LibraryAccess
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.presentation.base.BaseFragment
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.DocumentActions
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.utils.DocKind
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint

/** Home (Figma "Home"): every document on the device, filtered, sorted, as a list or grid. */
@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeScreenBinding>(FragmentHomeScreenBinding::inflate), DocumentListener {

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: DocumentAdapter

    /** Layout the list was last set up for; reset with each new view. */
    private var renderedGrid: Boolean? = null

    /** Built on each use: the views belong to the current view lifecycle. */
    private fun filterTabs() = with(binding) {
        listOf(
            tabAll to null,
            tabPdf to DocKind.Pdf,
            tabWord to DocKind.Word,
            tabExcel to DocKind.Excel,
            tabPpt to DocKind.Ppt
        )
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            AppNavigator.openDocument(findNavController(), requireContext(), uri)
        }
    }

    // API 30+: All files access is granted on a settings page; 29 and below: a runtime permission.
    private val allFilesSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refresh()
    }
    private val legacyStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refresh()
    }

    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.onBack()
        }
    }

    override fun initViews(savedInstanceState: Bundle?) {
        binding.appBar.applySystemBarPadding(top = true)
        renderedGrid = null
        adapter = DocumentAdapter(this, viewLifecycleOwner.lifecycleScope)
        binding.list.adapter = adapter
        binding.list.itemAnimator?.changeDuration = 120
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBack)

        childFragmentManager.setFragmentResultListener(CreateSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            CreateSheet.actionFrom(result)?.let(::onCreateAction)
        }
        childFragmentManager.setFragmentResultListener(DocumentActionsSheet.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            result.getString(DocumentActionsSheet.KEY_MESSAGE)?.let { Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show() }
            viewModel.refresh()
        }
    }

    override fun initListeners() = with(binding) {
        pro.setOnClickListener { comingSoon() }
        select.setOnClickListener { viewModel.onSelectModeClicked() }
        settings.setOnClickListener { AppNavigator.open(findNavController(), R.id.settings) }
        selectionClose.setOnClickListener { viewModel.exitSelection() }
        selectionShare.setOnClickListener { viewModel.onShareSelected() }
        selectionDelete.setOnClickListener { viewModel.onDeleteSelected() }

        search.doAfterTextChanged { viewModel.onQueryChanged(it?.toString().orEmpty()) }
        searchClear.setOnClickListener { search.text = null }

        actionImport.setOnClickListener { pickDocument() }
        actionImageToPdf.setOnClickListener { AppNavigator.open(findNavController(), R.id.images_to_pdf) }
        actionFolder.setOnClickListener { comingSoon() }

        filterTabs().forEach { (tab, kind) -> tab.setOnClickListener { viewModel.onKindSelected(kind) } }
        sort.setOnClickListener { showSortMenu() }
        viewList.setOnClickListener { viewModel.onViewModeSelected(grid = false) }
        viewGrid.setOnClickListener { viewModel.onViewModeSelected(grid = true) }

        fab.setOnClickListener { showCreateSheet() }
        empty.cta.setOnClickListener {
            if (viewModel.state.value.empty == HomeEmpty.NEEDS_ACCESS) requestAccess() else showCreateSheet()
        }
    }

    override fun observeViewModel() {
        viewModel.state.collectOnStarted(::render)
        viewModel.events.collectOnStarted(::handle)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    // ── Rendering ─────────────────────────────────────────────────────────────────────────────

    private fun render(state: HomeUiState) = with(binding) {
        actions.isVisible = !state.selectionMode
        brand.isVisible = !state.selectionMode
        selectionBar.isVisible = state.selectionMode
        selectionCount.text = getString(R.string.home_select_count, state.selected.size)
        selectionShare.isEnabled = state.selected.isNotEmpty()
        selectionDelete.isEnabled = state.selected.isNotEmpty()
        selectionBack.isEnabled = state.selectionMode

        searchClear.isVisible = state.query.isNotEmpty()
        filterTabs().forEach { (tab, kind) -> tab.setSelectedTab(kind == state.kind) }
        sortLabel.setText(state.sort.label())
        viewList.isSelected = !state.grid
        viewGrid.isSelected = state.grid
        summary.isInvisible = state.loading
        summary.text = resources.getQuantityString(
            if (state.access == LibraryAccess.UNSUPPORTED) R.plurals.home_summary_recent
            else R.plurals.home_summary,
            state.items.size, state.items.size
        )

        if (renderedGrid != state.grid) {
            renderedGrid = state.grid
            list.layoutManager = if (state.grid) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
            val side = resources.getDimensionPixelSize(if (state.grid) R.dimen.ds_grid_padding else R.dimen.ds_screen_padding)
            list.setPadding(side, list.paddingTop, side, list.paddingBottom)
            adapter.grid = state.grid
        }
        // Newest-first lists get new files at the top; keep them in view if the user was at the top.
        val recycler = list
        val atTop = !recycler.canScrollVertically(-1)
        adapter.submitList(state.items) { if (atTop) recycler.scrollToPosition(0) }

        loading.isVisible = state.loading
        val emptyState = state.empty
        emptyScroll.isVisible = emptyState != null
        list.isVisible = emptyState == null && !state.loading
        fab.isVisible = emptyState == null && !state.loading && !state.selectionMode
        when (emptyState) {
            HomeEmpty.NO_DOCUMENTS -> empty.bind(R.string.home_empty_title, R.string.home_empty_body, R.string.home_empty_cta)
            HomeEmpty.NEEDS_ACCESS -> empty.bind(R.string.home_access_title, R.string.home_access_body, R.string.home_access_cta)
            HomeEmpty.NO_MATCHES -> empty.bind(R.string.home_no_match_title, R.string.home_no_match_body, null)
            null -> Unit
        }
    }

    private fun ViewHomeEmptyBinding.bind(title: Int, body: Int, cta: Int?) {
        this.title.setText(title)
        this.body.setText(body)
        this.cta.isVisible = cta != null
        cta?.let(this.cta::setText)
    }

    private fun TextView.setSelectedTab(selected: Boolean) {
        isSelected = selected
        typeface = ResourcesCompat.getFont(context, if (selected) R.font.inter_semibold else R.font.inter_medium)
    }

    private fun DocumentSort.label() = when (this) {
        DocumentSort.DATE_MODIFIED -> R.string.home_sort_date
        DocumentSort.NAME -> R.string.home_sort_name
        DocumentSort.SIZE -> R.string.home_sort_size
    }

    private fun handle(event: HomeUiEvent) {
        when (event) {
            is HomeUiEvent.Open -> open(event.document)
            is HomeUiEvent.ShowMessage -> showMessage(event.text)
            is HomeUiEvent.Share -> share(event.documents)
            is HomeUiEvent.ConfirmDelete -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.home_delete_many_title, event.count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.doc_delete) { _, _ -> viewModel.onDeleteConfirmed() }
                .show()
        }
    }

    // ── DocumentListener ──────────────────────────────────────────────────────────────────────

    override fun onDocumentClick(document: LibraryDocument) = viewModel.onDocumentClicked(document)

    override fun onDocumentLongClick(document: LibraryDocument) = viewModel.onDocumentLongClicked(document)

    override fun onStarClick(document: LibraryDocument) {
        viewModel.onStarClicked(document)
    }

    override fun onMoreClick(document: LibraryDocument) = DocumentActionsSheet.show(childFragmentManager, document)

    // ── Actions ───────────────────────────────────────────────────────────────────────────────

    private fun open(document: LibraryDocument) =
        AppNavigator.openDocument(findNavController(), requireContext(), document.ref.toUri(), document.name)

    private fun share(documents: List<LibraryDocument>) {
        val ctx = requireContext()
        if (documents.size == 1) {
            DocumentActions.share(ctx, documents.first().ref.toUri())
            return
        }
        val uris = ArrayList(documents.map { DocumentActions.exportUri(ctx, it.ref.toUri()) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.home_select_share))) }
    }

    private fun showSortMenu() {
        val options = DocumentSort.entries
        PopupMenu(requireContext(), binding.sortLabel).apply {
            options.forEachIndexed { i, sort ->
                menu.add(0, i, i, sort.label()).apply {
                    isCheckable = true
                    isChecked = sort == viewModel.state.value.sort
                }
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                viewModel.onSortSelected(options[item.itemId])
                true
            }
        }.show()
    }

    private fun showCreateSheet() {
        if (childFragmentManager.findFragmentByTag(CreateSheet.TAG) == null) {
            CreateSheet().show(childFragmentManager, CreateSheet.TAG)
        }
    }

    private fun onCreateAction(action: CreateAction) {
        val nav = findNavController()
        when (action) {
            CreateAction.IMPORT_FILES -> pickDocument()
            CreateAction.SCAN -> AppNavigator.open(nav, R.id.scan)
            CreateAction.IMAGES, CreateAction.IMAGE_TO_PDF -> AppNavigator.open(nav, R.id.images_to_pdf)
            CreateAction.PDF_TO_IMAGE -> AppNavigator.open(nav, R.id.pdf_to_images)
            CreateAction.PDF_TO_WORD -> AppNavigator.open(nav, R.id.pdf_to_word)
            CreateAction.WORD_TO_PDF -> comingSoon()
        }
    }

    private fun pickDocument() {
        runCatching { openDocument.launch(AppNavigator.OPEN_DOC_MIMES) }
            .onFailure { AppNavigator.openPdfViewer(findNavController()) }
    }

    private fun requestAccess() {
        if (StorageAccess.needsSettingsScreen()) {
            runCatching { allFilesSettings.launch(StorageAccess.settingsIntent(requireContext())) }
        } else {
            legacyStorage.launch(StorageAccess.LEGACY_PERMISSION)
        }
    }

    private fun comingSoon() = Toast.makeText(requireContext(), R.string.coming_soon, Toast.LENGTH_SHORT).show()
}
