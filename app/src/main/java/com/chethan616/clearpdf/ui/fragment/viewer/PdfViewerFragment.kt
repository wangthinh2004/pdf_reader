package com.chethan616.clearpdf.ui.fragment.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.PdfPageBookmarksManager
import com.chethan616.clearpdf.data.repository.PdfServiceLocator
import com.chethan616.clearpdf.data.repository.RecentFilesManager
import com.chethan616.clearpdf.databinding.FragmentPdfViewerBinding
import com.chethan616.clearpdf.databinding.ItemViewerToolBinding
import com.chethan616.clearpdf.domain.usecase.OpenPdfUseCase
import com.chethan616.clearpdf.office.OfficeEngine
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.SaveDialog
import com.chethan616.clearpdf.ui.common.UnsavedChanges
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.fragment.tool.ToolUi
import com.chethan616.clearpdf.ui.fragment.tool.viewModelFactory
import com.chethan616.clearpdf.ui.viewmodel.FindMatch
import com.chethan616.clearpdf.ui.viewmodel.OcrTextBlock
import com.chethan616.clearpdf.ui.viewmodel.PdfViewerUiState
import com.chethan616.clearpdf.ui.viewmodel.PdfViewerViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The document reader and annotation editor. Pages scroll in a zoomable list; the bottom bar holds
 * the editing tools, the top bar search and document actions. Office, text and image documents
 * arrive here already converted to PDF by the view model.
 */
class PdfViewerFragment : Fragment(), PdfPageView.PageHost, PageTouchController.Callbacks {

    private var _binding: FragmentPdfViewerBinding? = null
    private val binding get() = _binding!!

    private val vm: PdfViewerViewModel by viewModels {
        viewModelFactory { PdfViewerViewModel(OpenPdfUseCase(PdfServiceLocator.pdfViewer)) }
    }
    override val annotations: AnnotationsViewModel by viewModels()

    private lateinit var touch: PageTouchController
    private lateinit var adapter: PagesAdapter
    private val saveDialog = SaveDialog(this)

    private var state = PdfViewerUiState()
    private var chromeVisible = true
    private var darkPages = false
    private var exitAfterSave = false
    private var pendingSave: Map<Int, List<Markup>>? = null
    private var restoredPage = false
    private var trimJob: Job? = null
    private var autoScroll = false
    private val toolViews = mutableMapOf<EditTool, ItemViewerToolBinding>()

    private val palette = intArrayOf(
        0xFF00BCD4.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(),
        0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(),
        0xFF000000.toInt(), 0xFFFFFFFF.toInt()
    )

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.openPdf(requireContext(), uri)
        }
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            } ?: return@launch
            placeImage(bmp, isSignature = false)
        }
    }

    private val backGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = onBack()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = AppNavigator.parseUriArg(arguments?.getString(AppNavigator.ARG_URI))
        if (savedInstanceState == null && uri != null && vm.uiState.value.document == null) {
            vm.openPdf(requireContext(), uri)
        }
        darkPages = savedInstanceState?.getBoolean(KEY_DARK) ?: false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPdfViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        touch = PageTouchController(annotations, ::blocks, this)
        restoredPage = savedInstanceState != null
        setupInsets()
        setupToolbar()
        setupSearch()
        setupPages()
        setupTools()
        setupEmptyState()

        binding.unlock.setOnClickListener { submitPassword() }
        binding.password.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { submitPassword(); true } else false }
        binding.officeHintClose.setOnClickListener { vm.dismissOfficeEngineHint(requireContext()) }
        binding.officeHintGet.setOnClickListener {
            OfficeEngine.focusSettingsSection.value = true
            AppNavigator.open(findNavController(), R.id.settings)
        }

        collectWhileStarted(vm.uiState, ::render)
        collectWhileStarted(annotations.version) {
            invalidatePages()
            renderEditorBars()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAutoScroll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DARK, darkPages)
    }

    override fun onDestroyView() {
        stopAutoScroll()
        setSystemBarsVisible(true)
        super.onDestroyView()
        _binding = null
    }

    // ── Setup ─────────────────────────────────────────────────────────────────────────────────

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.topBar.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            binding.empty.updatePadding(top = bars.top + requireContext().dp(56), bottom = bars.bottom)
            binding.root.post { updateListPadding() }
            insets
        }
    }

    /** Keeps the first and last page clear of the bars. */
    private fun updateListPadding() {
        val b = _binding ?: return
        b.pages.updatePadding(
            top = b.topBar.height + requireContext().dp(8),
            bottom = b.bottomBar.height + requireContext().dp(8)
        )
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { onBack() }
        binding.toolbar.inflateMenu(R.menu.pdf_viewer)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> openSearch()
                R.id.action_jump -> showJumpDialog()
                R.id.action_bookmark -> toggleBookmark()
                R.id.action_bookmarks -> showBookmarks()
                R.id.action_dark_pages -> { darkPages = !darkPages; invalidatePages(); updateMenu() }
                R.id.action_auto_scroll -> if (autoScroll) stopAutoScroll() else startAutoScroll()
                R.id.action_reset_zoom -> binding.pages.resetZoom()
                R.id.action_ocr -> vm.triggerOcrForAllPages(requireContext())
                R.id.action_share -> showShareDialog()
                R.id.action_save -> save(leaveAfter = false)
                R.id.action_open_another -> openPicker()
            }
            true
        }
        binding.toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateListPadding() }
        binding.bottomBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updateListPadding()
        }
    }

    private fun updateMenu() {
        val menu: Menu = binding.toolbar.menu
        menu.findItem(R.id.action_dark_pages)?.setTitle(if (darkPages) R.string.viewer_page_colors_original else R.string.viewer_page_dark_mode)
        menu.findItem(R.id.action_auto_scroll)?.setTitle(if (autoScroll) R.string.viewer_auto_scroll_pause else R.string.viewer_auto_scroll_start)
        val page = state.currentPage
        val bookmarked = bookmarkUri()?.let { PdfPageBookmarksManager.get(requireContext(), it).contains(page) } == true
        menu.findItem(R.id.action_bookmark)?.setTitle(if (bookmarked) R.string.viewer_bookmark_remove else R.string.viewer_bookmark_add)
        val hasDoc = state.document != null
        listOf(R.id.action_search, R.id.action_jump, R.id.action_bookmark, R.id.action_bookmarks, R.id.action_dark_pages,
            R.id.action_auto_scroll, R.id.action_reset_zoom, R.id.action_ocr, R.id.action_share, R.id.action_save)
            .forEach { menu.findItem(it)?.isVisible = hasDoc }
    }

    private fun setupSearch() {
        binding.searchClose.setOnClickListener { closeSearch() }
        binding.searchInput.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                vm.searchTextInDocument(requireContext(), v.text.toString())
                hideKeyboard(v)
                true
            } else false
        }
        binding.searchNext.setOnClickListener { vm.nextMatch() }
        binding.searchPrev.setOnClickListener { vm.prevMatch() }
    }

    private fun setupPages() {
        adapter = PagesAdapter()
        val lm = LinearLayoutManager(requireContext())
        binding.pages.layoutManager = lm
        binding.pages.adapter = adapter
        binding.pages.itemAnimator = null
        binding.pages.setItemViewCacheSize(2)
        binding.pages.onScaleChanged = { scale ->
            // Sharper pages once zoomed in: re-render what is on screen at a higher width.
            if (scale > 1.4f) renderVisible(scale)
        }
        binding.pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = onPagesScrolled()
        })
    }

    private fun setupEmptyState() {
        val accent = requireContext().color(R.color.tool_open)
        ToolUi.hero(
            binding.hero, R.drawable.ic_picture_as_pdf, accent,
            getString(R.string.viewer_open_pdf), getString(R.string.viewer_select_file),
            getString(R.string.viewer_pick_pdf)
        ) { openPicker() }
    }

    private fun openPicker() {
        runCatching { pdfPicker.launch(AppNavigator.OPEN_DOC_MIMES) }
    }

    // ── State ─────────────────────────────────────────────────────────────────────────────────

    private fun render(s: PdfViewerUiState) {
        val previous = state
        state = s
        val b = binding
        val hasDoc = s.document != null
        val askingPassword = s.passwordRequired && s.passwordUri != null
        // A document handed to us (recents, another app, a tool's output) shows the loading curtain
        // rather than flashing the "Open a PDF" card before its pages arrive.
        val handed = arguments?.getString(AppNavigator.ARG_URI) != null
        val opening = !hasDoc && !askingPassword && s.errorMessage == null && (s.isLoading || handed)
        val firstPageReady = s.pageBitmaps.any { it != null }

        b.loading.isVisible = opening || (hasDoc && !firstPageReady)
        b.pages.isVisible = hasDoc
        b.empty.isVisible = !hasDoc && !askingPassword && !opening
        b.passwordCard.isVisible = askingPassword
        b.bottomBar.isVisible = hasDoc && chromeVisible
        if (askingPassword) {
            b.passwordLayout.error = if (s.passwordAttemptFailed) getString(R.string.pdf_password_wrong) else null
        }
        ToolUi.status(b.error, s.errorMessage, isError = true)
        b.officeHint.isVisible = s.showOfficeEngineHint && hasDoc

        b.toolbar.title = s.fileName.ifBlank { getString(R.string.viewer_title) }
        b.toolbar.subtitle = if (hasDoc && s.pageCount > 0) {
            getString(R.string.viewer_page_of, s.currentPage + 1, s.pageCount)
        } else null

        // A new document: fresh editor state, and jump to where the reader left off.
        val docKey = s.document?.uri?.toString()
        if (docKey != null && docKey != annotations.documentKey) {
            annotations.resetFor(docKey)
            restoredPage = false
            renderTools()
        }
        if (hasDoc && s.pageCount != adapter.itemCount) adapter.notifyDataSetChanged()
        else if (hasDoc) {
            // Rebind only the pages whose bitmap changed.
            val old = previous.pageBitmaps
            s.pageBitmaps.forEachIndexed { i, bmp -> if (old.getOrNull(i) !== bmp) adapter.notifyItemChanged(i) }
            if (previous.ocrBlocksByPage !== s.ocrBlocksByPage || previous.findMatches !== s.findMatches ||
                previous.currentMatchIndex != s.currentMatchIndex) invalidatePages()
        }
        if (hasDoc && !restoredPage && s.pageCount > 0) {
            restoredPage = true
            if (s.currentPage > 0) (b.pages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(s.currentPage, 0)
        }

        // Search
        b.searchCount.text = when {
            s.findQuery.isBlank() -> ""
            s.findMatches.isEmpty() -> getString(R.string.viewer_find_no_results)
            else -> getString(R.string.viewer_find_results, s.currentMatchIndex + 1, s.findMatches.size)
        }
        if (s.currentMatchIndex >= 0 && (s.currentMatchIndex != previous.currentMatchIndex || s.findMatches !== previous.findMatches)) {
            s.findMatches.getOrNull(s.currentMatchIndex)?.let { scrollToPage(it.pageIndex) }
        }

        // Saving
        ToolUi.setBusy(b.save, s.isExporting, R.drawable.ic_save, "")
        if (s.exportMessage != null || s.exportError != null) onExportFinished(s)

        updateMenu()
    }

    private fun onExportFinished(s: PdfViewerUiState) {
        val message = s.exportMessage ?: s.exportError ?: return
        val pendingSnapshot = pendingSave
        if (s.exportMessage != null && pendingSnapshot != null) annotations.markSaved(pendingSnapshot)
        pendingSave = null
        vm.clearExportFeedback()
        if (s.exportMessage != null && exitAfterSave) {
            exitAfterSave = false
            findNavController().popBackStack()
            return
        }
        exitAfterSave = false
        val bar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        val out = s.lastExportedUri
        if (s.exportMessage != null && out != null) bar.setAction(R.string.open) { AppNavigator.openPdfViewer(findNavController(), out) }
        bar.anchorView = binding.bottomBar.takeIf { it.isVisible }
        bar.show()
        renderEditorBars()
    }

    private fun submitPassword() {
        val uri = state.passwordUri ?: return
        val pwd = binding.password.text?.toString().orEmpty()
        if (pwd.isEmpty()) return
        hideKeyboard(binding.password)
        vm.openPdf(requireContext(), uri, pwd)
    }

    // ── Pages ─────────────────────────────────────────────────────────────────────────────────

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.Holder>() {
        override fun getItemCount() = if (state.document != null) state.pageCount.coerceAtLeast(0) else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val page = PdfPageView(parent.context)
            page.host = this@PdfViewerFragment
            page.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = parent.context.dp(8)
            }
            page.elevation = parent.context.dp(1).toFloat()
            return Holder(page)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = holder.itemView as PdfPageView
            val bmp = state.pageBitmaps.getOrNull(position)?.takeUnless { it.isRecycled }
            page.bind(position, bmp, knownAspect())
            page.darkPages = darkPages
            if (bmp == null) vm.renderPage(requireContext(), position, renderWidth(1f))
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view)
    }

    /** The aspect of any rendered page, as a placeholder height for pages not rendered yet. */
    private fun knownAspect(): Float = state.pageBitmaps.firstOrNull { it != null && !it.isRecycled }
        ?.let { it.height.toFloat() / it.width.coerceAtLeast(1) } ?: 1.4142f

    private fun renderWidth(scale: Float): Int =
        ((binding.pages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * scale.coerceAtMost(3f)).toInt().coerceAtMost(3200)

    private fun renderVisible(scale: Float) {
        val lm = binding.pages.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return
        val last = lm.findLastVisibleItemPosition()
        for (p in first..last) vm.renderPage(requireContext(), p, renderWidth(scale))
    }

    private fun visiblePages(): List<PdfPageView> {
        val rv = _binding?.pages ?: return emptyList()
        return (0 until rv.childCount).mapNotNull { rv.getChildAt(it) as? PdfPageView }
    }

    private fun invalidatePages() = visiblePages().forEach { it.darkPages = darkPages; it.invalidate() }

    /** The page under the middle of the screen. */
    private fun centerPage(): PdfPageView? {
        val rv = binding.pages
        val cy = rv.height / 2f
        return visiblePages().minByOrNull { v ->
            val (_, top) = rv.toScreen(0f, v.top.toFloat())
            val (_, bottom) = rv.toScreen(0f, v.bottom.toFloat())
            when {
                cy < top -> top - cy
                cy > bottom -> cy - bottom
                else -> 0f
            }
        }
    }

    private fun onPagesScrolled() {
        val page = centerPage()?.pageIndex ?: return
        if (page < 0) return
        val ctx = requireContext()
        vm.onPageChanged(page)
        vm.prefetchAround(ctx, page, renderWidth(1f))
        trimJob?.cancel()
        trimJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(200)
            vm.trimBitmapCache(page)
            state.originalUri?.let { RecentFilesManager.updateProgress(ctx, it, page, state.pageCount) }
        }
    }

    private fun scrollToPage(page: Int) {
        (binding.pages.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, binding.pages.paddingTop)
    }

    // ── PageHost ──────────────────────────────────────────────────────────────────────────────

    override fun blocks(page: Int): List<OcrTextBlock> = state.ocrBlocksByPage[page].orEmpty()

    override fun matches(page: Int): List<FindMatch> = state.findMatches.filter { it.pageIndex == page }

    override fun currentMatch(): FindMatch? = state.findMatches.getOrNull(state.currentMatchIndex)

    override fun draft(page: Int): Markup? = touch.draft(page)

    override fun onPageTouch(view: PdfPageView, event: MotionEvent): Boolean = touch.onTouch(view, event)

    // ── Touch callbacks ───────────────────────────────────────────────────────────────────────

    override fun onTapEmpty() = setChromeVisible(!chromeVisible)

    override fun onMarkupSelectionChanged() = renderEditorBars()

    override fun onTextSelectionChanged() = renderEditorBars()

    override fun onRequestText(page: Int, at: NPoint, note: Boolean, existing: MarkupRef?) {
        val current = existing?.let { annotations.get(it) }
        val initialText = when (current) {
            is Markup.TextBox -> current.text
            is Markup.Note -> current.text
            else -> ""
        }
        val initialColor = current?.color() ?: annotations.color
        AnnotationEditor.show(requireContext(), if (note) R.string.anno_note_title else R.string.anno_text_title, initialText, initialColor, palette) { text, color ->
            if (text.isBlank()) {
                if (existing != null) annotations.remove(existing)
                return@show
            }
            when {
                existing != null && current is Markup.TextBox -> annotations.replace(existing, current.copy(text = text, color = color))
                existing != null && current is Markup.Note -> annotations.replace(existing, current.copy(text = text, color = color))
                note -> annotations.add(page, Markup.Note(System.nanoTime(), at, text, color))
                else -> annotations.add(page, Markup.TextBox(System.nanoTime(), at, text, color, DEFAULT_FONT_SIZE))
            }
        }
    }

    // ── Chrome ────────────────────────────────────────────────────────────────────────────────

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        val b = _binding ?: return
        b.topBar.animate().translationY(if (visible) 0f else -b.topBar.height.toFloat()).setDuration(180).start()
        b.bottomBar.isVisible = visible && state.document != null
        setSystemBarsVisible(visible)
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val window = activity?.window ?: return
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) ctrl.show(WindowInsetsCompat.Type.systemBars()) else ctrl.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ── Tools ─────────────────────────────────────────────────────────────────────────────────

    private data class ToolSpec(val tool: EditTool, @DrawableRes val icon: Int, @StringRes val label: Int)

    private val toolSpecs = listOf(
        ToolSpec(EditTool.Draw, R.drawable.ic_draw, R.string.viewer_pen),
        ToolSpec(EditTool.Highlight, R.drawable.ic_highlight, R.string.viewer_highlight),
        ToolSpec(EditTool.Text, R.drawable.ic_text_fields, R.string.anno_text_title),
        ToolSpec(EditTool.Note, R.drawable.ic_edit_note, R.string.anno_note_title),
        ToolSpec(EditTool.Rect, R.drawable.ic_crop_square, R.string.viewer_rect),
        ToolSpec(EditTool.Ellipse, R.drawable.ic_radio_button_unchecked, R.string.viewer_oval),
        ToolSpec(EditTool.Line, R.drawable.ic_horizontal_rule, R.string.viewer_line),
        ToolSpec(EditTool.Arrow, R.drawable.ic_arrow_right_alt, R.string.viewer_arrow),
        ToolSpec(EditTool.Image, R.drawable.ic_add_photo_alternate, R.string.viewer_add_image),
        ToolSpec(EditTool.None, R.drawable.ic_gesture, R.string.viewer_sign),
        ToolSpec(EditTool.Eraser, R.drawable.ic_auto_fix_normal, R.string.viewer_eraser),
        ToolSpec(EditTool.Select, R.drawable.ic_select_all, R.string.viewer_lasso)
    )

    private fun setupTools() {
        val ctx = requireContext()
        toolViews.clear()
        toolSpecs.forEach { spec ->
            val b = ItemViewerToolBinding.inflate(layoutInflater, binding.tools, true)
            b.icon.setImageResource(spec.icon)
            b.label.setText(spec.label)
            b.tool.contentDescription = getString(spec.label)
            if (spec.tool == EditTool.None) {
                // "Sign" is an action, not a mode: it opens the signature pad.
                b.tool.setOnClickListener {
                    SignatureDialog.show(ctx) { bmp -> placeImage(bmp, isSignature = true) }
                }
            } else {
                toolViews[spec.tool] = b
                b.tool.setOnClickListener { selectTool(spec.tool) }
            }
        }

        palette.forEach { c ->
            binding.colors.addView(swatch(c) {
                annotations.color = c
                renderTools()
            })
        }
        binding.width.value = annotations.strokeWidth.coerceIn(0.002f, 0.03f)
        binding.width.addOnChangeListener { _, value, fromUser -> if (fromUser) annotations.strokeWidth = value }
        binding.undo.setOnClickListener { annotations.undo(centerPage()?.pageIndex ?: state.currentPage) }
        binding.clearPage.setOnClickListener { centerPage()?.pageIndex?.let(annotations::clearPage) }
        binding.toolDone.setOnClickListener { selectTool(EditTool.None) }

        ToolUi.primaryButton(binding.save, ctx.color(R.color.accent_blue))
        binding.save.setOnClickListener { save(leaveAfter = false) }

        binding.markupDone.setOnClickListener {
            annotations.selection = null
            annotations.bump()
        }
        binding.markupDelete.setOnClickListener { annotations.selection?.let(annotations::remove) }
        binding.markupEdit.setOnClickListener {
            val ref = annotations.selection ?: return@setOnClickListener
            val m = annotations.get(ref) ?: return@setOnClickListener
            val at = (m as? Markup.TextBox)?.position ?: (m as? Markup.Note)?.anchor ?: return@setOnClickListener
            onRequestText(ref.page, at, m is Markup.Note, ref)
        }
        palette.forEach { c ->
            binding.markupColors.addView(swatch(c) {
                val ref = annotations.selection ?: return@swatch
                annotations.get(ref)?.let { annotations.replace(ref, it.recolored(c)) }
            })
        }

        setupTextActions()
        renderTools()
    }

    private fun swatch(color: Int, onClick: () -> Unit): View {
        val ctx = requireContext()
        return FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.bg_pill)
            backgroundTintList = ColorStateList.valueOf(color)
            foreground = ctx.getDrawable(R.drawable.swatch_ring)
            tag = color
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)).apply {
                setMargins(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6))
            }
        }
    }

    private fun selectTool(tool: EditTool) {
        val next = if (annotations.tool == tool) EditTool.None else tool
        annotations.tool = next
        annotations.selection = null
        annotations.textSelection = null
        binding.pages.panLocked = next != EditTool.None
        if (next == EditTool.Image) imagePicker.launch("image/*")
        annotations.bump()
        renderTools()
    }

    private fun renderTools() {
        val b = _binding ?: return
        val ctx = requireContext()
        val accent = ctx.color(R.color.accent_blue)
        val ink = ctx.color(R.color.app_text)
        toolViews.forEach { (tool, v) ->
            val on = annotations.tool == tool
            v.tool.backgroundTintList = ColorStateList.valueOf(if (on) ctx.accentWash(accent) else android.graphics.Color.TRANSPARENT)
            v.icon.tint(if (on) accent else ink)
            v.label.setTextColor(if (on) accent else ctx.color(R.color.app_text_secondary))
        }
        val t = annotations.tool
        val showOptions = t != EditTool.None
        b.toolOptions.isVisible = showOptions
        b.colorsScroll.isVisible = t !in setOf(EditTool.Eraser, EditTool.Select, EditTool.Image)
        b.width.isVisible = t in setOf(EditTool.Draw, EditTool.Highlight, EditTool.Line, EditTool.Arrow)
        for (i in 0 until b.colors.childCount) {
            val v = b.colors.getChildAt(i)
            v.isSelected = v.tag == annotations.color
            v.alpha = if (v.tag == annotations.color) 1f else 0.6f
        }
        binding.pages.panLocked = t != EditTool.None
        renderEditorBars()
    }

    /** Text-selection and selected-markup bars, plus the Save button state. */
    private fun renderEditorBars() {
        val b = _binding ?: return
        val textSel = annotations.textSelection
        b.textActions.isVisible = textSel != null
        val ref = annotations.selection
        val m = ref?.let { annotations.get(it) }
        b.markupActions.isVisible = m != null
        if (m != null) {
            b.markupEdit.isVisible = m is Markup.TextBox || m is Markup.Note
            b.markupColors.isVisible = m.isRecolorable()
            for (i in 0 until b.markupColors.childCount) {
                val v = b.markupColors.getChildAt(i)
                v.alpha = if (v.tag == m.color()) 1f else 0.6f
            }
        }
        b.save.isVisible = annotations.hasMarkups() || annotations.isDirty()
        backGuard.isEnabled = true
    }

    // ── Text selection actions ────────────────────────────────────────────────────────────────

    private fun setupTextActions() {
        val ctx = requireContext()
        listOf(
            Triple(R.drawable.ic_content_copy, R.string.copy) { copySelection() },
            Triple(R.drawable.ic_highlight, R.string.viewer_highlight) { markSelection(TextMark.Highlight) },
            Triple(R.drawable.ic_format_underlined, R.string.viewer_underline) { markSelection(TextMark.Underline) },
            Triple(R.drawable.ic_strikethrough_s, R.string.viewer_strike) { markSelection(TextMark.Strike) },
            Triple(R.drawable.ic_share, R.string.share) { shareSelection() },
            Triple(R.drawable.ic_search, R.string.selection_search) { searchSelection() },
            Triple(R.drawable.ic_select_all, R.string.viewer_select_all) { selectAllOnPage() },
            Triple(R.drawable.ic_close, R.string.close) { clearTextSelection() }
        ).forEach { (icon, label, action) ->
            val button = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(label)
                setIconResource(icon)
                iconSize = ctx.dp(18)
                isAllCaps = false
                strokeWidth = 0
                setTextColor(ctx.color(R.color.app_text))
                iconTint = ColorStateList.valueOf(ctx.color(R.color.app_text))
                setOnClickListener { action() }
            }
            binding.textActionsRow.addView(button)
        }
    }

    private enum class TextMark { Highlight, Underline, Strike }

    private fun selectionBlocks(): Pair<TextSelection, List<OcrTextBlock>>? {
        val sel = annotations.textSelection ?: return null
        return sel to blocks(sel.page).readingOrder()
    }

    private fun copySelection() {
        val (sel, blocks) = selectionBlocks() ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", sel.text(blocks)))
        Toast.makeText(requireContext(), R.string.selection_copied, Toast.LENGTH_SHORT).show()
        clearTextSelection()
    }

    private fun markSelection(kind: TextMark) {
        val (sel, blocks) = selectionBlocks() ?: return
        val group = System.nanoTime()
        sel.ranges(blocks).forEach { (b, s, e) ->
            val m = when (kind) {
                TextMark.Highlight -> Markup.TextHighlight(b.id, s, e, HIGHLIGHT_YELLOW, groupId = group)
                TextMark.Underline -> Markup.TextLine(b.id, s, e, annotations.color, 0.0025f, strikeThrough = false, groupId = group)
                TextMark.Strike -> Markup.TextLine(b.id, s, e, 0xFFFF453A.toInt(), 0.0025f, strikeThrough = true, groupId = group)
            }
            annotations.add(sel.page, m)
        }
        clearTextSelection()
    }

    private fun shareSelection() {
        val (sel, blocks) = selectionBlocks() ?: return
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, sel.text(blocks)) }
        runCatching { startActivity(Intent.createChooser(send, null)) }
    }

    private fun searchSelection() {
        val (sel, blocks) = selectionBlocks() ?: return
        val text = sel.text(blocks).trim()
        clearTextSelection()
        openSearch()
        binding.searchInput.setText(text)
        vm.searchTextInDocument(requireContext(), text)
    }

    private fun selectAllOnPage() {
        val sel = annotations.textSelection ?: return
        val blocks = blocks(sel.page).readingOrder()
        if (blocks.isEmpty()) return
        annotations.textSelection = TextSelection(sel.page, TextPos(0, 0), TextPos(blocks.lastIndex, blocks.last().text.length))
        annotations.bump()
    }

    private fun clearTextSelection() {
        annotations.textSelection = null
        annotations.bump()
    }

    // ── Images & signatures ───────────────────────────────────────────────────────────────────

    /** Places [bmp] centred on the page in the middle of the screen, then selects it for moving. */
    private fun placeImage(bmp: Bitmap, isSignature: Boolean) {
        val pageView = centerPage() ?: return
        val page = pageView.pageIndex
        val w = (if (isSignature) 0.45f else 0.5f)
        val ratio = bmp.height.toFloat() / bmp.width.coerceAtLeast(1) / pageView.aspect
        val h = (w * ratio).coerceAtMost(0.9f)
        // Centre on the part of the page that is visible.
        val rv = binding.pages
        val (_, top) = rv.toScreen(0f, pageView.top.toFloat())
        val (_, bottom) = rv.toScreen(0f, pageView.bottom.toFloat())
        val visibleCenter = ((rv.height / 2f).coerceIn(top, bottom) - top) / (bottom - top).coerceAtLeast(1f)
        val cy = visibleCenter.coerceIn(h / 2f, 1f - h / 2f)
        val m = Markup.Image(System.nanoTime(), bmp, NPoint(0.5f - w / 2f, cy - h / 2f), NPoint(0.5f + w / 2f, cy + h / 2f), isSignature)
        annotations.add(page, m)
        annotations.tool = EditTool.Select
        annotations.selection = MarkupRef(page, annotations.markups(page).lastIndex)
        annotations.bump()
        renderTools()
    }

    // ── Search ────────────────────────────────────────────────────────────────────────────────

    private fun openSearch() {
        binding.toolbar.isVisible = false
        binding.searchBar.isVisible = true
        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        hideKeyboard(binding.searchInput)
        binding.searchInput.setText("")
        binding.searchBar.isVisible = false
        binding.toolbar.isVisible = true
        vm.clearSearch()
    }

    private fun hideKeyboard(v: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ── Menu actions ──────────────────────────────────────────────────────────────────────────

    private fun bookmarkUri(): Uri? = state.originalUri ?: state.document?.uri

    private fun toggleBookmark() {
        val uri = bookmarkUri() ?: return
        PdfPageBookmarksManager.toggle(requireContext(), uri, state.currentPage)
        updateMenu()
    }

    private fun showBookmarks() {
        val uri = bookmarkUri() ?: return
        val pages = PdfPageBookmarksManager.get(requireContext(), uri).sorted()
        if (pages.isEmpty()) {
            Toast.makeText(requireContext(), R.string.viewer_bookmark_add, Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.viewer_bookmark_page)
            .setItems(pages.map { getString(R.string.viewer_page_of, it + 1, state.pageCount) }.toTypedArray()) { _, i -> scrollToPage(pages[i]) }
            .show()
    }

    private fun showJumpDialog() {
        val ctx = requireContext()
        val layout = layoutInflater.inflate(R.layout.view_text_field, null) as com.google.android.material.textfield.TextInputLayout
        layout.hint = "${getString(R.string.viewer_page_label)} (1–${state.pageCount})"
        val input = layout.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        val frame = FrameLayout(ctx).apply { setPadding(ctx.dp(24), 0, ctx.dp(24), 0); addView(layout) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.viewer_jump_to_page)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.viewer_go) { _, _ ->
                input.text?.toString()?.toIntOrNull()?.let { scrollToPage((it - 1).coerceIn(0, state.pageCount - 1)) }
            }
            .show()
    }

    private val autoScrollFrame = object : Choreographer.FrameCallback {
        private var last = 0L
        override fun doFrame(frameTimeNanos: Long) {
            val b = _binding ?: return
            if (!autoScroll) { last = 0L; return }
            if (last != 0L) {
                val dt = ((frameTimeNanos - last).coerceAtMost(50_000_000L)) / 1e9f
                val px = (36f * resources.displayMetrics.density * dt).toInt().coerceAtLeast(1)
                if (!b.pages.canScrollVertically(1)) { stopAutoScroll(); return }
                b.pages.scrollBy(0, px)
            }
            last = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startAutoScroll() {
        if (autoScroll) return
        autoScroll = true
        Choreographer.getInstance().postFrameCallback(autoScrollFrame)
        updateMenu()
    }

    private fun stopAutoScroll() {
        autoScroll = false
        Choreographer.getInstance().removeFrameCallback(autoScrollFrame)
        if (_binding != null) updateMenu()
    }

    private fun showShareDialog() {
        val ext = state.fileName.substringAfterLast('.', "").uppercase()
        val isConverted = ext.isNotBlank() && ext != "PDF"
        ShareDialog.show(requireContext(), originalExt = if (isConverted) ext else null) { original, password ->
            sharePayload(original, password)
        }
    }

    private fun sharePayload(original: Boolean, password: String?) {
        val ctx = requireContext().applicationContext
        val s = state
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }
                    fun provider(f: java.io.File) = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", f)
                    if (original) {
                        val orig = s.originalUri ?: return@runCatching null
                        val safe = s.fileName.ifBlank { "document" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val out = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
                        ctx.contentResolver.openInputStream(orig)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                            ?: return@runCatching null
                        provider(out) to (ctx.contentResolver.getType(orig) ?: "application/octet-stream")
                    } else {
                        val pdf = s.document?.uri ?: return@runCatching null
                        if (!password.isNullOrBlank()) {
                            val out = java.io.File(dir, "protected_${System.currentTimeMillis()}.pdf")
                            val outUri = provider(out)
                            com.kyant.pdfcore.security.PdfSecurityService.encryptToUri(ctx, pdf, outUri, password)
                            outUri to "application/pdf"
                        } else {
                            val shareUri = if (pdf.scheme == "file") provider(java.io.File(pdf.path!!)) else pdf
                            shareUri to "application/pdf"
                        }
                    }
                }.getOrNull()
            } ?: return@launch
            val (uri, mime) = payload
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(send, null)) }
        }
    }

    // ── Save & exit ───────────────────────────────────────────────────────────────────────────

    private fun save(leaveAfter: Boolean) {
        if (state.document == null || state.isExporting) return
        val base = state.fileName.substringBeforeLast('.').ifBlank { "Document" }
        saveDialog.show("${base}_Edited") { fileName, folder ->
            val snapshot = annotations.snapshot()
            val overlays = snapshot.mapValues { (page, list) -> list.toExportOverlays(blocks(page)) }.filterValues { it.isNotEmpty() }
            pendingSave = snapshot
            exitAfterSave = leaveAfter
            vm.exportEditedPdf(requireContext(), overlays, fileName, folder)
        }
    }

    private fun onBack() {
        when {
            binding.searchBar.isVisible -> closeSearch()
            !chromeVisible -> setChromeVisible(true)
            annotations.textSelection != null -> clearTextSelection()
            annotations.selection != null -> { annotations.selection = null; annotations.bump() }
            annotations.tool != EditTool.None -> selectTool(EditTool.None)
            state.document != null && annotations.isDirty() -> UnsavedChanges.confirm(
                requireContext(),
                onSave = { save(leaveAfter = true) },
                onDiscard = { findNavController().popBackStack() }
            )
            else -> findNavController().popBackStack()
        }
    }

    private companion object {
        const val KEY_DARK = "dark_pages"
        /** Default text box size as a fraction of the page height. */
        const val DEFAULT_FONT_SIZE = 0.022f
        val HIGHLIGHT_YELLOW = 0xFFFFD60A.toInt()
    }
}
