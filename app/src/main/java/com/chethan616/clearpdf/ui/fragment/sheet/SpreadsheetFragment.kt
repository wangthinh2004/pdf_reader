package com.chethan616.clearpdf.ui.fragment.sheet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.FragmentSpreadsheetBinding
import com.chethan616.clearpdf.ui.common.ActionSheet
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.DocumentActions
import com.chethan616.clearpdf.ui.common.SheetAction
import com.chethan616.clearpdf.ui.common.UnsavedChanges
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.isDarkTheme
import com.chethan616.clearpdf.ui.viewmodel.BorderPreset
import com.chethan616.clearpdf.ui.viewmodel.SpreadsheetViewModel
import com.chethan616.clearpdf.utils.xlsx.Axis
import com.chethan616.clearpdf.utils.xlsx.ColorSpec
import com.chethan616.clearpdf.utils.xlsx.PaintTheme
import com.chethan616.clearpdf.utils.xlsx.ResolvedStyle
import com.chethan616.clearpdf.utils.xlsx.SheetLayout
import com.chethan616.clearpdf.utils.xlsx.StylePatch
import com.chethan616.clearpdf.utils.xlsx.ValidationKind
import com.chethan616.clearpdf.utils.xlsx.XlsxColors
import com.chethan616.clearpdf.utils.xlsx.XlsxPainter
import com.chethan616.clearpdf.utils.xlsx.XlsxRefs
import com.chethan616.clearpdf.utils.xlsx.XlsxSheet
import com.chethan616.clearpdf.utils.xlsx.XlsxWorkbook
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spreadsheet viewer and editor: the file's own look in a scrollable, zoomable grid with sheet
 * tabs. Editing (the pencil) adds the formula bar input, formatting tools, range handles,
 * list dropdowns, row / column changes, undo / redo and Save / Save as.
 */
class SpreadsheetFragment : Fragment(), SheetGridView.Listener {

    private var _binding: FragmentSpreadsheetBinding? = null
    private val binding get() = _binding!!
    private val vm: SpreadsheetViewModel by viewModels()

    private var state = SpreadsheetViewModel.UiState()
    private var sheetIndex = -1
    private var selection: GridSelection? = null
    private var editMode = false
    private var editing = false
    private var exitAfterSave = false
    private var layout: SheetLayout? = null
    private var layoutKey: Pair<XlsxSheet, Int>? = null
    private var painter: XlsxPainter? = null
    private var painterWb: XlsxWorkbook? = null
    private var matches: List<Pair<Int, Int>> = emptyList()
    private var currentMatch = 0
    private var searchJob: Job? = null
    private val toolButtons = mutableMapOf<Int, MaterialButton>()
    private var suppressTabCallback = false

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) vm.save(requireContext(), uri, ::onSaved) else exitAfterSave = false
    }

    private val backGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = requestBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppNavigator.parseUriArg(arguments?.getString(AppNavigator.ARG_URI))?.let { vm.load(requireContext(), it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSpreadsheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true, bottom = true, imeBottom = true)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        binding.toolbar.setNavigationOnClickListener { requestBack() }
        binding.toolbar.inflateMenu(R.menu.spreadsheet)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> if (!state.saving) save(asCopy = false)
                R.id.action_search -> openSearch()
                R.id.action_edit -> toggleEditMode()
                R.id.action_share -> vm.shareableUri(requireContext()) { u -> u?.let { DocumentActions.share(requireContext(), it) } }
                R.id.action_export_pdf -> vm.exportToPdf(requireContext()) { u -> u?.let { AppNavigator.openPdfViewer(findNavController(), it) } }
                R.id.action_save_as -> save(asCopy = true)
            }
            true
        }
        binding.grid.listener = this

        // Formula bar
        binding.cellCopy.setOnClickListener {
            val text = anchorEditText()
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(selection?.let { XlsxRefs.cellRef(it.anchorR, it.anchorC) }, text))
            Toast.makeText(requireContext(), R.string.selection_copied, Toast.LENGTH_SHORT).show()
        }
        binding.cellCommit.setOnClickListener { commitDraft(); moveSelection(1); startEditing() }
        binding.cellValue.setOnFocusChangeListener { _, focused -> if (focused && !editing) startEditing() }
        binding.cellValue.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_NEXT || id == EditorInfo.IME_ACTION_DONE ||
                (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) {
                commitDraft(); moveSelection(1); startEditing(); true
            } else false
        }

        // Search
        binding.searchClose.setOnClickListener { closeSearch() }
        binding.searchInput.doAfterTextChanged { runSearch(it?.toString().orEmpty()) }
        binding.searchNext.setOnClickListener { goToMatch(1) }
        binding.searchPrev.setOnClickListener { goToMatch(-1) }

        binding.sheets.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (suppressTabCallback) return
                if (editing) commitDraft()
                sheetIndex = tab.tag as Int
                selection = null
                render()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        setupEditTools()
        collectWhileStarted(vm.state) { state = it; render() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        toolButtons.clear()
        layout = null; layoutKey = null; painter = null; painterWb = null
        _binding = null
    }

    // ── State ─────────────────────────────────────────────────────────────────────────────────

    private fun visibleSheets(wb: XlsxWorkbook) = wb.sheets.indices.filter { !wb.sheets[it].hidden }

    private fun currentSheet(): XlsxSheet? {
        val wb = state.workbook ?: return null
        val visible = visibleSheets(wb)
        val idx = if (sheetIndex in visible) sheetIndex else visible.firstOrNull() ?: 0
        return wb.sheets.getOrNull(idx)
    }

    private fun sheetIdx(): Int {
        val wb = state.workbook ?: return 0
        val visible = visibleSheets(wb)
        return if (sheetIndex in visible) sheetIndex else visible.firstOrNull() ?: 0
    }

    private fun render() {
        val b = _binding ?: return
        val ctx = requireContext()
        val wb = state.workbook
        val sheet = currentSheet()
        b.toolbar.title = (if (state.dirty) "• " else "") + state.fileName.ifBlank { getString(R.string.viewer_title) }
        b.loading.isVisible = state.isLoading
        b.message.isVisible = !state.isLoading && sheet == null
        b.message.text = state.error ?: getString(R.string.sheet_empty)
        b.gridCard.isVisible = sheet != null

        val menu = b.toolbar.menu
        menu.findItem(R.id.action_save)?.isVisible = state.dirty && state.editable
        menu.findItem(R.id.action_search)?.isVisible = !editMode && sheet != null
        menu.findItem(R.id.action_edit)?.apply {
            isVisible = state.editable
            if (editMode) icon = null else setIcon(R.drawable.ic_edit)
            setTitle(if (editMode) R.string.sheet_done else R.string.sheet_edit)
        }
        menu.findItem(R.id.action_save_as)?.isVisible = state.editable
        listOf(R.id.action_share, R.id.action_export_pdf).forEach { menu.findItem(it)?.isVisible = sheet != null }

        if (wb == null || sheet == null) return

        // Sheet tabs
        val visible = visibleSheets(wb)
        b.sheets.isVisible = visible.size > 1
        if (b.sheets.tabCount != visible.size) {
            suppressTabCallback = true
            b.sheets.removeAllTabs()
            visible.forEach { i -> b.sheets.addTab(b.sheets.newTab().setText(wb.sheets[i].name).setTag(i)) }
            suppressTabCallback = false
        }
        val tabPos = visible.indexOf(sheetIdx())
        if (tabPos >= 0 && b.sheets.selectedTabPosition != tabPos) {
            suppressTabCallback = true
            b.sheets.getTabAt(tabPos)?.select()
            suppressTabCallback = false
        }

        // Geometry and painter, rebuilt only when the sheet or the model changed.
        val key = sheet to state.version
        if (layoutKey != key) {
            layout = SheetLayout(sheet)
            layoutKey = key
            painter?.invalidate()
        }
        if (painterWb !== wb) {
            val dark = ctx.isDarkTheme()
            painter = XlsxPainter(wb, PaintTheme(dark, surface(), ctx.color(R.color.app_text), if (dark) 0xFF34353A.toInt() else 0xFFE2E4E9.toInt()))
            painterWb = wb
        }
        val dark = ctx.isDarkTheme()
        b.grid.bind(
            sheet, layout!!, painter!!,
            GridColors(
                surface = surface(),
                headerBand = if (dark) 0xFF26272B.toInt() else 0xFFF3F5F8.toInt(),
                headerText = if (dark) 0xFFB5B6BF.toInt() else 0xFF5E6068.toInt(),
                headerDivider = if (dark) 0xFF3A3B40.toInt() else 0xFFDADDE3.toInt(),
                accent = ctx.color(R.color.accent_blue)
            )
        )
        b.grid.editMode = editMode
        b.grid.selection = selection
        b.grid.setMatches(matches, matches.getOrNull(currentMatch))
        renderFormulaBar()
        renderEditTools()
    }

    private fun surface() = if (requireContext().isDarkTheme()) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    private fun anchorStyle(): ResolvedStyle? {
        val sel = selection ?: return null
        val wb = state.workbook ?: return null
        return wb.styles.resolve(currentSheet()?.cell(sel.anchorR, sel.anchorC)?.style ?: 0)
    }

    private fun anchorEditText(): String {
        val sel = selection ?: return ""
        val wb = state.workbook ?: return ""
        return currentSheet()?.cell(sel.anchorR, sel.anchorC)?.let { wb.editText(it) } ?: ""
    }

    private fun renderFormulaBar() {
        val b = _binding ?: return
        val sel = selection
        val sheet = currentSheet()
        b.formulaBar.isVisible = sel != null && sheet != null
        if (sel == null || sheet == null) return
        b.cellRef.text = when {
            sel.range.isSingle || sheet.mergeAt(sel.anchorR, sel.anchorC) == sel.range -> XlsxRefs.cellRef(sel.anchorR, sel.anchorC)
            sel.isWholeColumn -> XlsxRefs.colLetter(sel.range.c1) + ":" + XlsxRefs.colLetter(sel.range.c2)
            sel.isWholeRow -> "${sel.range.r1 + 1}:${sel.range.r2 + 1}"
            else -> sel.range.toRef()
        }
        val canEdit = state.editable && editMode
        b.cellValue.isFocusable = canEdit
        b.cellValue.isFocusableInTouchMode = canEdit
        b.cellCommit.isVisible = editing
        if (!editing) {
            val text = anchorEditText()
            if (b.cellValue.text.toString() != text) b.cellValue.setText(text)
        }
    }

    // ── Grid callbacks ────────────────────────────────────────────────────────────────────────

    override fun onSelect(selection: GridSelection) = select(selection)

    override fun onTapSelected(r: Int, c: Int) {
        val v = currentSheet()?.validationAt(r, c)
        if (editMode && v != null && v.kind == ValidationKind.LIST) onDropdown(r, c) else startEditing()
    }

    override fun onDropdown(r: Int, c: Int) {
        if (!state.editable) return
        val sheet = currentSheet() ?: return
        val wb = state.workbook ?: return
        val v = sheet.validationAt(r, c) ?: return
        val items = wb.listItems(v, sheet)
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setItems(items.toTypedArray()) { _, i ->
                vm.setCellText(sheetIdx(), r, c, items[i])
                editing = false
            }
            .show()
    }

    override fun onColumnResize(col: Int, widthChars: Float) = vm.setColWidth(sheetIdx(), col, widthChars)

    // ── Editing ───────────────────────────────────────────────────────────────────────────────

    private fun select(sel: GridSelection) {
        if (editing) commitDraft()
        selection = sel
        render()
    }

    private fun toggleEditMode() {
        if (editMode) {
            if (editing) commitDraft()
            editMode = false
            hideKeyboard()
        } else {
            editMode = true
            closeSearch()
        }
        render()
    }

    private fun startEditing() {
        if (!state.editable || selection == null) return
        if (!editMode) editMode = true
        editing = true
        val b = _binding ?: return
        b.cellValue.isFocusable = true
        b.cellValue.isFocusableInTouchMode = true
        b.cellValue.setText(anchorEditText())
        b.cellValue.setSelection(b.cellValue.text.length)
        b.cellValue.requestFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.cellValue, InputMethodManager.SHOW_IMPLICIT)
        render()
    }

    private fun commitDraft() {
        val sel = selection ?: return
        if (!editing) return
        editing = false
        val draft = binding.cellValue.text.toString()
        val sheet = currentSheet()
        val wb = state.workbook
        val validation = sheet?.validationAt(sel.anchorR, sel.anchorC)
        if (sheet != null && wb != null && validation != null && validation.kind == ValidationKind.LIST && validation.strict && draft.isNotEmpty()) {
            val items = wb.listItems(validation, sheet)
            if (items.isNotEmpty() && items.none { it.equals(draft, ignoreCase = true) }) {
                Toast.makeText(requireContext(), R.string.sheet_invalid_value, Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (draft != anchorEditText()) vm.setCellText(sheetIdx(), sel.anchorR, sel.anchorC, draft)
    }

    private fun moveSelection(dr: Int) {
        val s = selection ?: return
        val sheet = currentSheet() ?: return
        val lay = layout ?: return
        val m = sheet.mergeAt(s.anchorR, s.anchorC)
        var r = ((m?.r2 ?: s.anchorR) + dr).coerceIn(0, lay.nRows - 1)
        while (r < lay.nRows - 1 && sheet.isRowHidden(r)) r++
        selection = GridSelection.cell(r, s.anchorC, sheet)
        binding.grid.reveal(r, s.anchorC)
    }

    private fun styleSel(patch: StylePatch) {
        val sel = selection ?: return
        if (editing) commitDraft()
        vm.applyStyle(sheetIdx(), sel.range, patch)
    }

    // ── Edit toolbar ──────────────────────────────────────────────────────────────────────────

    private data class Tool(val id: Int, @DrawableRes val icon: Int, @StringRes val label: Int, val needsSelection: Boolean = true)

    private val tools = listOf(
        Tool(0, R.drawable.ic_undo, R.string.sheet_undo, false),
        Tool(1, R.drawable.ic_redo, R.string.sheet_redo, false),
        Tool(2, R.drawable.ic_format_bold, R.string.sheet_bold),
        Tool(3, R.drawable.ic_format_italic, R.string.sheet_italic),
        Tool(4, R.drawable.ic_format_underlined, R.string.sheet_underline),
        Tool(5, R.drawable.ic_format_strikethrough, R.string.sheet_strike),
        Tool(6, R.drawable.ic_format_color_text, R.string.sheet_text_color),
        Tool(7, R.drawable.ic_format_color_fill, R.string.sheet_fill_color),
        Tool(8, R.drawable.ic_format_align_left, R.string.sheet_align),
        Tool(9, R.drawable.ic_border_all, R.string.sheet_borders),
        Tool(10, R.drawable.ic_numbers, R.string.sheet_number_format),
        Tool(11, R.drawable.ic_table_rows, R.string.sheet_rows_cols),
        Tool(12, R.drawable.ic_format_clear, R.string.sheet_clear),
        Tool(13, R.drawable.ic_save_as, R.string.sheet_save_as, false)
    )

    private fun setupEditTools() {
        val ctx = requireContext()
        tools.forEach { t ->
            val button = MaterialButton(ctx, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                setIconResource(t.icon)
                contentDescription = getString(t.label)
                iconSize = ctx.dp(22)
                layoutParams = android.widget.LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48))
                setOnClickListener { onTool(t.id) }
                setOnLongClickListener { Toast.makeText(ctx, t.label, Toast.LENGTH_SHORT).show(); true }
            }
            toolButtons[t.id] = button
            binding.editTools.addView(button)
        }
    }

    private fun renderEditTools() {
        val b = _binding ?: return
        val ctx = requireContext()
        b.editToolsScroll.isVisible = editMode
        if (!editMode) return
        val st = anchorStyle()
        val hasSel = selection != null
        val accent = ctx.color(R.color.accent_blue)
        val ink = ctx.color(R.color.app_text)
        tools.forEach { t ->
            val button = toolButtons[t.id] ?: return@forEach
            val active = when (t.id) {
                2 -> st?.bold == true
                3 -> st?.italic == true
                4 -> st?.underline == true
                5 -> st?.strike == true
                else -> false
            }
            val enabled = when (t.id) {
                0 -> state.canUndo
                1 -> state.canRedo
                else -> !t.needsSelection || hasSel
            }
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.35f
            button.iconTint = ColorStateList.valueOf(if (active) accent else ink)
            button.backgroundTintList = ColorStateList.valueOf(if (active) (accent and 0x00FFFFFF) or 0x24000000 else android.graphics.Color.TRANSPARENT)
        }
    }

    private fun onTool(id: Int) {
        val st = anchorStyle()
        when (id) {
            0 -> { if (editing) commitDraft(); vm.undo() }
            1 -> vm.redo()
            2 -> styleSel(StylePatch(bold = !(st?.bold ?: false)))
            3 -> styleSel(StylePatch(italic = !(st?.italic ?: false)))
            4 -> styleSel(StylePatch(underline = !(st?.underline ?: false)))
            5 -> styleSel(StylePatch(strike = !(st?.strike ?: false)))
            6 -> pickColor(fill = false)
            7 -> pickColor(fill = true)
            8 -> showAlign()
            9 -> showBorders()
            10 -> showNumberFormats()
            11 -> showRowsCols()
            12 -> selection?.let { vm.clearRange(sheetIdx(), it.range) }
            13 -> save(asCopy = true)
        }
    }

    private fun pickColor(fill: Boolean) {
        val ctx = requireContext()
        val theme = state.workbook?.styles?.themeColors ?: XlsxColors.DefaultTheme
        val themeColors = (0 until 10).map { theme.getOrElse(it) { XlsxColors.DefaultTheme[it] } }
        val grid = GridLayout(ctx).apply {
            columnCount = 10
            val pad = ctx.dp(20)
            setPadding(pad, ctx.dp(8), pad, 0)
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        fun add(color: Int, spec: ColorSpec) {
            grid.addView(FrameLayout(ctx).apply {
                setBackgroundResource(R.drawable.bg_rounded_small)
                backgroundTintList = ColorStateList.valueOf(color or 0xFF000000.toInt())
                foreground = ctx.getDrawable(R.drawable.swatch_ring)
                setOnClickListener {
                    styleSel(if (fill) StylePatch(fill = spec) else StylePatch(fontColor = spec))
                    dialog?.dismiss()
                }
            }, GridLayout.LayoutParams().apply {
                width = ctx.dp(26); height = ctx.dp(26)
                setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
            })
        }
        themeColors.forEachIndexed { i, c -> add(c, ColorSpec(theme = i)) }
        StandardColors.forEach { c -> add(c, ColorSpec.ofArgb(c)) }
        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (fill) R.string.sheet_fill_color else R.string.sheet_text_color)
            .setView(grid)
            .setNeutralButton(if (fill) R.string.sheet_no_fill else R.string.sheet_automatic) { _, _ ->
                styleSel(if (fill) StylePatch(clearFill = true) else StylePatch(fontColor = ColorSpec(theme = 1)))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAlign() {
        val ctx = requireContext()
        val st = anchorStyle()
        val accent = ctx.color(R.color.accent_blue)
        val actions = listOf(
            Triple(R.drawable.ic_format_align_left, "left", true),
            Triple(R.drawable.ic_format_align_center, "center", true),
            Triple(R.drawable.ic_format_align_right, "right", true),
            Triple(R.drawable.ic_vertical_align_top, "top", false),
            Triple(R.drawable.ic_vertical_align_center, "center", false),
            Triple(R.drawable.ic_vertical_align_bottom, "bottom", false)
        ).map { (icon, value, horizontal) ->
            val label = getString(if (horizontal) R.string.sheet_horizontal else R.string.sheet_vertical) + " · " + value
            val on = if (horizontal) st?.hAlign == value else st?.vAlign == value
            SheetAction(icon, label, if (on) accent else ctx.color(R.color.app_text_secondary)) {
                styleSel(if (horizontal) StylePatch(hAlign = value) else StylePatch(vAlign = value))
            }
        } + SheetAction(R.drawable.ic_text_fields, getString(R.string.sheet_wrap), if (st?.wrap == true) accent else ctx.color(R.color.app_text_secondary)) {
            styleSel(StylePatch(wrap = !(st?.wrap ?: false)))
        }
        ActionSheet.show(ctx, getString(R.string.sheet_align), actions)
    }

    private fun showBorders() {
        val ctx = requireContext()
        val ink = ctx.color(R.color.app_text_secondary)
        val sel = selection ?: return
        val presets = listOf(
            Triple(BorderPreset.ALL, R.drawable.ic_border_all, "All borders"),
            Triple(BorderPreset.OUTER, R.drawable.ic_border_outer, "Outside borders"),
            Triple(BorderPreset.THICK_OUTER, R.drawable.ic_border_style, "Thick outside borders"),
            Triple(BorderPreset.INNER, R.drawable.ic_border_inner, "Inside borders"),
            Triple(BorderPreset.TOP, R.drawable.ic_border_top, "Top border"),
            Triple(BorderPreset.BOTTOM, R.drawable.ic_border_bottom, "Bottom border"),
            Triple(BorderPreset.LEFT, R.drawable.ic_border_left, "Left border"),
            Triple(BorderPreset.RIGHT, R.drawable.ic_border_right, "Right border"),
            Triple(BorderPreset.NONE, R.drawable.ic_border_clear, "No borders")
        )
        ActionSheet.show(ctx, getString(R.string.sheet_borders), presets.map { (preset, icon, label) ->
            SheetAction(icon, label, ink) {
                if (editing) commitDraft()
                vm.applyBorders(sheetIdx(), sel.range, preset)
            }
        })
    }

    private fun showNumberFormats() {
        val current = anchorStyle()?.numFmtCode ?: "General"
        val labels = NumberFormats.map { it.first }.toTypedArray()
        val checked = NumberFormats.indexOfFirst { it.second == current }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sheet_number_format)
            .setSingleChoiceItems(labels, checked) { d, i ->
                styleSel(StylePatch(numFmtCode = NumberFormats[i].second))
                d.dismiss()
            }
            .show()
    }

    private fun showRowsCols() {
        val s = selection ?: return
        val sheet = currentSheet() ?: return
        val ctx = requireContext()
        val ink = ctx.color(R.color.app_text_secondary)
        val danger = ctx.color(R.color.app_danger)
        val rows = if (s.isWholeColumn) 1 else s.range.r2 - s.range.r1 + 1
        val cols = if (s.isWholeRow) 1 else s.range.c2 - s.range.c1 + 1
        fun act(block: () -> Unit) {
            if (editing) commitDraft()
            block()
            selection = GridSelection.cell(s.anchorR, s.anchorC, sheet)
        }
        ActionSheet.show(ctx, getString(R.string.sheet_rows_cols), listOf(
            SheetAction(R.drawable.ic_vertical_align_top, getString(R.string.sheet_insert_row_above), ink) { act { vm.insertLines(sheetIdx(), Axis.ROW, s.range.r1, rows) } },
            SheetAction(R.drawable.ic_vertical_align_bottom, getString(R.string.sheet_insert_row_below), ink) { act { vm.insertLines(sheetIdx(), Axis.ROW, s.range.r2 + 1, rows) } },
            SheetAction(R.drawable.ic_delete_outline, getString(R.string.sheet_delete_rows), danger, destructive = true) { act { vm.deleteLines(sheetIdx(), Axis.ROW, s.range.r1, rows) } },
            SheetAction(R.drawable.ic_format_align_left, getString(R.string.sheet_insert_col_left), ink) { act { vm.insertLines(sheetIdx(), Axis.COL, s.range.c1, cols) } },
            SheetAction(R.drawable.ic_format_align_right, getString(R.string.sheet_insert_col_right), ink) { act { vm.insertLines(sheetIdx(), Axis.COL, s.range.c2 + 1, cols) } },
            SheetAction(R.drawable.ic_delete_outline, getString(R.string.sheet_delete_cols), danger, destructive = true) { act { vm.deleteLines(sheetIdx(), Axis.COL, s.range.c1, cols) } }
        ))
    }

    // ── Search ────────────────────────────────────────────────────────────────────────────────

    private fun openSearch() {
        binding.toolbar.isVisible = false
        binding.searchBar.isVisible = true
        binding.searchInput.requestFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        val b = _binding ?: return
        if (!b.searchBar.isVisible) return
        hideKeyboard()
        b.searchInput.setText("")
        b.searchBar.isVisible = false
        b.toolbar.isVisible = true
        matches = emptyList()
        render()
    }

    /** Scans the sheet off the main thread, debounced so typing never waits on a big sheet. */
    private fun runSearch(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        val sheet = currentSheet()
        val p = painter
        if (q.isBlank() || sheet == null || p == null) {
            matches = emptyList(); updateSearchCount(); _binding?.grid?.setMatches(matches, null); return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(180)
            // Formatting cell text uses the painter's cache, which is main-thread only: snapshot first.
            val cells = sheet.rows.flatMap { (r, row) -> row.cells.map { (c, cell) -> Triple(r, c, cell) } }
            val texts = cells.map { (r, c, cell) -> Triple(r, c, p.display(cell)) }
            val found = withContext(Dispatchers.Default) {
                val out = ArrayList<Pair<Int, Int>>()
                for ((r, c, text) in texts) {
                    if (!isActive) break
                    if (text.contains(q, ignoreCase = true)) { out.add(r to c); if (out.size >= 5000) break }
                }
                out
            }
            matches = found
            currentMatch = 0
            updateSearchCount()
            _binding?.grid?.setMatches(matches, matches.firstOrNull())
            matches.firstOrNull()?.let { (r, c) -> _binding?.grid?.reveal(r, c) }
        }
    }

    private fun goToMatch(delta: Int) {
        if (matches.isEmpty()) return
        currentMatch = ((currentMatch + delta) % matches.size + matches.size) % matches.size
        val (r, c) = matches[currentMatch]
        binding.grid.setMatches(matches, matches[currentMatch])
        binding.grid.reveal(r, c)
        updateSearchCount()
    }

    private fun updateSearchCount() {
        val b = _binding ?: return
        b.searchCount.text = when {
            b.searchInput.text.isNullOrBlank() -> ""
            matches.isEmpty() -> getString(R.string.viewer_find_no_results)
            else -> getString(R.string.viewer_find_results, currentMatch + 1, matches.size)
        }
    }

    // ── Save & exit ───────────────────────────────────────────────────────────────────────────

    private fun save(asCopy: Boolean) {
        if (editing) commitDraft()
        if (asCopy) { saveAsLauncher.launch(vm.suggestedSaveAsName()); return }
        vm.save(requireContext(), null, ::onSaved)
    }

    private fun onSaved(result: SpreadsheetViewModel.SaveResult) {
        if (_binding == null) return
        when (result) {
            is SpreadsheetViewModel.SaveResult.Saved -> {
                Toast.makeText(requireContext(), R.string.sheet_saved, Toast.LENGTH_SHORT).show()
                if (exitAfterSave) findNavController().popBackStack()
            }
            is SpreadsheetViewModel.SaveResult.NeedsSaveAs -> saveAsLauncher.launch(vm.suggestedSaveAsName())
            is SpreadsheetViewModel.SaveResult.Failed -> {
                exitAfterSave = false
                Toast.makeText(requireContext(), R.string.sheet_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestBack() {
        val b = _binding ?: return
        when {
            b.searchBar.isVisible -> closeSearch()
            editing -> { editing = false; hideKeyboard(); b.cellValue.clearFocus(); render() }
            state.dirty -> UnsavedChanges.confirm(
                requireContext(),
                onSave = { exitAfterSave = true; save(asCopy = false) },
                onDiscard = { findNavController().popBackStack() },
                title = getString(R.string.sheet_unsaved_title),
                body = getString(R.string.sheet_unsaved_body),
                saveLabel = getString(R.string.sheet_save)
            )
            else -> findNavController().popBackStack()
        }
    }

    private fun hideKeyboard() {
        val v = view ?: return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private companion object {
        val NumberFormats = listOf(
            "General" to "General",
            "Number" to "#,##0.00",
            "Integer" to "0",
            "Currency" to "\"$\"#,##0.00",
            "Accounting" to "_(\"$\"* #,##0.00_);_(\"$\"* (#,##0.00);_(\"$\"* \"-\"??_);_(@_)",
            "Percent" to "0%",
            "Percent (2dp)" to "0.00%",
            "Scientific" to "0.00E+00",
            "Short date" to "dd-mm-yyyy",
            "Long date" to "d mmmm yyyy",
            "Time" to "h:mm:ss",
            "Text" to "@"
        )

        /** Office's ten standard colours. */
        val StandardColors = intArrayOf(
            0xFFC00000.toInt(), 0xFFFF0000.toInt(), 0xFFFFC000.toInt(), 0xFFFFFF00.toInt(), 0xFF92D050.toInt(),
            0xFF00B050.toInt(), 0xFF00B0F0.toInt(), 0xFF0070C0.toInt(), 0xFF002060.toInt(), 0xFF7030A0.toInt()
        )
    }
}
