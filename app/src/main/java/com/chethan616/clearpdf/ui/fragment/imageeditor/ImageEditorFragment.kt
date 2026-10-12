package com.chethan616.clearpdf.ui.fragment.imageeditor

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.FragmentImageEditorBinding
import com.chethan616.clearpdf.databinding.ItemViewerToolBinding
import com.chethan616.clearpdf.imageeditor.engine.Adjust
import com.chethan616.clearpdf.imageeditor.engine.BrushKind
import com.chethan616.clearpdf.imageeditor.engine.CropMask
import com.chethan616.clearpdf.imageeditor.engine.ExportFormat
import com.chethan616.clearpdf.imageeditor.engine.FilterPreset
import com.chethan616.clearpdf.imageeditor.engine.MetadataMode
import com.chethan616.clearpdf.imageeditor.engine.ShapeKind
import com.chethan616.clearpdf.imageeditor.engine.TextFont
import com.chethan616.clearpdf.imageeditor.engine.WatermarkPosition
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.UnsavedChanges
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.viewmodel.ImageEditorViewModel
import com.chethan616.clearpdf.ui.viewmodel.ImageEditorViewModel.MoreTool
import com.chethan616.clearpdf.ui.viewmodel.ImageEditorViewModel.Tool
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full image editor: the image with live previews, a panel for the active tool and a tool dock
 * (Crop · Adjust · Filters · Draw · Text · More). Edits are non-destructive (see
 * [ImageEditorViewModel]); export renders at full resolution.
 */
class ImageEditorFragment : Fragment() {

    private var _binding: FragmentImageEditorBinding? = null
    private val binding get() = _binding!!
    private val vm: ImageEditorViewModel by viewModels()

    private lateinit var kit: PanelKit
    private var state = ImageEditorViewModel.UiState()
    private var panelKey: Any? = null
    /** Pushes new state values into the controls of the current panel without rebuilding it. */
    private var syncPanel: (ImageEditorViewModel.UiState) -> Unit = {}
    private var comparing = false
    private var messageJob: Job? = null
    private var shownMessage: String? = null
    private val dock = mutableMapOf<Tool, ItemViewerToolBinding>()

    private val watermarkImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.setWatermarkImage(requireContext(), it) }
    }

    private val backGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = requestBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppNavigator.parseUriArg(arguments?.getString(AppNavigator.ARG_URI))?.let { vm.load(requireContext(), it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImageEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true, bottom = true, imeBottom = true)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backGuard)
        kit = PanelKit(requireContext(), binding.panel)
        binding.toolbar.setNavigationOnClickListener { requestBack() }
        binding.toolbar.inflateMenu(R.menu.image_editor)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_undo -> vm.undo()
                R.id.action_redo -> vm.redo()
                R.id.action_export -> showExport()
            }
            true
        }
        binding.crop.accent = requireContext().color(R.color.accent_blue)
        // Press and hold to see the original.
        binding.compare.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { comparing = true; renderStage(); v.isPressed = true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { comparing = false; renderStage(); v.isPressed = false; v.performClick() }
            }
            true
        }
        setupDock()
        collectWhileStarted(vm.state) { render(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dock.clear()
        panelKey = null
        _binding = null
    }

    private fun requestBack() {
        if (state.hasEdits) {
            UnsavedChanges.confirm(
                requireContext(),
                onSave = { vm.saveToGallery(onSaved = { findNavController().popBackStack() }) },
                onDiscard = { findNavController().popBackStack() },
                body = getString(R.string.unsaved_body_image)
            )
        } else findNavController().popBackStack()
    }

    // ── Dock ──────────────────────────────────────────────────────────────────────────────────

    private fun setupDock() {
        listOf(
            Triple(Tool.Crop, R.drawable.ic_crop, R.string.ie_tool_crop),
            Triple(Tool.Adjust, R.drawable.ic_tune, R.string.ie_tool_adjust),
            Triple(Tool.Filters, R.drawable.ic_auto_awesome, R.string.ie_tool_filters),
            Triple(Tool.Draw, R.drawable.ic_brush, R.string.ie_tool_draw),
            Triple(Tool.Text, R.drawable.ic_text_fields, R.string.ie_tool_text),
            Triple(Tool.More, R.drawable.ic_more_horiz, R.string.ie_tool_more)
        ).forEach { (tool, icon, label) ->
            val b = ItemViewerToolBinding.inflate(layoutInflater, binding.dock, false)
            b.icon.setImageResource(icon)
            b.label.setText(label)
            b.tool.setOnClickListener { if (state.tool != tool) vm.selectTool(tool) }
            binding.dock.addView(b.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            dock[tool] = b
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────────────────────

    private fun render(s: ImageEditorViewModel.UiState) {
        state = s
        val b = _binding ?: return
        val ctx = requireContext()
        b.toolbar.title = s.fileName.ifBlank { getString(R.string.image_editor_title) }
        b.toolbar.menu.findItem(R.id.action_undo)?.let { it.isEnabled = s.canUndo; it.icon?.alpha = if (s.canUndo) 255 else 90 }
        b.toolbar.menu.findItem(R.id.action_redo)?.let { it.isEnabled = s.canRedo; it.icon?.alpha = if (s.canRedo) 255 else 90 }
        b.loading.isVisible = s.isLoading
        val failed = !s.isLoading && (s.error != null || s.preview == null)
        b.error.isVisible = failed
        b.error.text = s.error ?: getString(R.string.open_image_failed)
        b.panel.isVisible = !s.isLoading && !failed
        b.dock.isVisible = !s.isLoading && !failed
        b.progress.isVisible = s.exporting || (s.isRendering && s.tool != Tool.Crop)
        if (s.exporting) {
            b.progress.isIndeterminate = false
            b.progress.setProgressCompat((s.exportProgress * 100).roundToInt(), true)
        } else b.progress.isIndeterminate = true
        b.compare.isVisible = s.hasEdits && s.tool != Tool.Crop && !s.isLoading

        val accent = ctx.color(R.color.accent_blue)
        dock.forEach { (tool, v) ->
            val on = s.tool == tool
            v.icon.tint(if (on) accent else ctx.color(R.color.app_text))
            v.label.setTextColor(if (on) accent else ctx.color(R.color.app_text_secondary))
        }

        // Transient message
        if (s.message != shownMessage) {
            shownMessage = s.message
            b.message.isVisible = s.message != null
            b.message.text = s.message
            messageJob?.cancel()
            if (s.message != null) messageJob = viewLifecycleOwner.lifecycleScope.launch { delay(2400); vm.dismissMessage() }
        }

        renderStage()
        if (!s.isLoading && !failed) renderPanel(s)
    }

    private fun renderStage() {
        val b = _binding ?: return
        val s = state
        val crop = s.crop
        val cropping = s.tool == Tool.Crop && crop != null
        b.crop.isVisible = cropping
        b.stage.isVisible = !cropping && s.preview != null
        if (cropping) {
            b.crop.setImage(crop!!.working)
            val aspect = CropAspects.getOrElse(crop.aspectIndex) { CropAspects[0] }
            val ratio = when (aspect.second) {
                null -> null
                -1f -> crop.working.width / crop.working.height.toFloat()
                else -> aspect.second
            }
            b.crop.setMode(ratio, CropMasks.getOrElse(crop.outlineIndex) { CropMasks[0] }.second, crop.perspective)
            return
        }
        b.stage.bitmap = if (comparing) s.original else s.preview
        b.stage.input = when {
            comparing -> StageInput.None
            s.tool == Tool.Draw -> StageInput.Brush(s.brush) { vm.addStroke(it) }
            s.tool == Tool.Text -> StageInput.Text(s.text) { f -> vm.updateText(f) }
            s.tool == Tool.More && s.more == MoreTool.Background -> StageInput.Mask(s.bgBrushWidth, s.bgRestore) { vm.addMaskStroke(it) }
            else -> StageInput.None
        }
    }

    // ── Panels ────────────────────────────────────────────────────────────────────────────────

    private fun renderPanel(s: ImageEditorViewModel.UiState) {
        val key = when (s.tool) {
            Tool.Crop -> listOf(s.tool, s.crop != null, s.crop?.perspective)
            Tool.Adjust -> listOf(s.tool, s.selectedAdjust)
            Tool.Filters -> listOf(s.tool, s.filterThumbs.size, s.selectedFilter != null)
            Tool.Draw -> listOf(s.tool, s.brush.brush, s.brush.shape == ShapeKind.FloodFill)
            Tool.Text -> listOf(s.tool)
            Tool.More -> listOf(s.tool, s.more == MoreTool.Background, s.bgAvailable, s.bgBusy)
        }
        if (key != panelKey) {
            panelKey = key
            kit.clear()
            syncPanel = when (s.tool) {
                Tool.Crop -> buildCrop(s)
                Tool.Adjust -> buildAdjust(s)
                Tool.Filters -> buildFilters(s)
                Tool.Draw -> buildDraw(s)
                Tool.Text -> buildText(s)
                Tool.More -> if (s.more == MoreTool.Background) buildBackground(s) else buildMore()
            }
        }
        syncPanel(s)
    }

    private fun buildCrop(s: ImageEditorViewModel.UiState): (ImageEditorViewModel.UiState) -> Unit {
        val crop = s.crop ?: return {}
        val buttons = kit.iconRow(listOf(
            Triple(R.drawable.ic_rotate_left, getString(R.string.ie_tool_crop)) { vm.rotateCrop(-1) },
            Triple(R.drawable.ic_rotate_right, getString(R.string.ie_tool_crop)) { vm.rotateCrop(1) },
            Triple(R.drawable.ic_flip, getString(R.string.ie_tool_crop)) { vm.flipCrop(true) },
            Triple(R.drawable.ic_flip, getString(R.string.ie_tool_crop)) { vm.flipCrop(false) },
            Triple(R.drawable.ic_transform, getString(R.string.ie_perspective)) { vm.setPerspective(!state.crop!!.perspective) },
            Triple(R.drawable.ic_restore, getString(R.string.ie_tool_crop)) { vm.resetCrop() }
        ))
        buttons[3].rotation = 90f
        var straighten: (Float) -> Unit = {}
        var aspectChips = emptyList<com.google.android.material.chip.Chip>()
        var maskChips = emptyList<com.google.android.material.chip.Chip>()
        if (!crop.perspective) {
            straighten = kit.slider(getString(R.string.ie_straighten), -45f, 45f, crop.transform.straighten,
                format = { "${(it * 10).roundToInt() / 10f}°" }, onReset = { vm.straighten(0f) }) { vm.straighten(it) }
            aspectChips = kit.chips(CropAspects.map { (label, ratio) ->
                when (ratio) { null -> getString(R.string.ie_free); -1f -> getString(R.string.ie_original); else -> label }
            }, crop.aspectIndex) { vm.setAspect(it) }
            maskChips = kit.chips(CropMasks.map { it.first }, crop.outlineIndex, requireContext().color(R.color.accent_purple)) { vm.setOutline(it) }
        }
        kit.button(getString(R.string.ie_apply)) { applyCrop() }
        return { st ->
            val c = st.crop
            if (c != null) {
                kit.mark(buttons[2], c.transform.flipH)
                kit.mark(buttons[3], c.transform.flipV)
                kit.mark(buttons[4], c.perspective)
                straighten(c.transform.straighten)
                aspectChips.forEachIndexed { i, chip -> chip.isChecked = i == c.aspectIndex }
                maskChips.forEachIndexed { i, chip -> chip.isChecked = i == c.outlineIndex }
            }
        }
    }

    private fun applyCrop() {
        val crop = state.crop ?: return
        if (crop.perspective) vm.onPerspectiveCropped(binding.crop.perspectiveCorners())
        else vm.onCropped(binding.crop.cropRect(), CropMasks.getOrElse(crop.outlineIndex) { CropMasks[0] }.second)
    }

    private fun buildAdjust(s: ImageEditorViewModel.UiState): (ImageEditorViewModel.UiState) -> Unit {
        val a = s.selectedAdjust
        val set = kit.slider(adjustLabel(a), a.min, a.max, s.adjustValues[a] ?: a.default, onReset = { vm.resetAdjust(a) }) { vm.setAdjust(a, it) }
        val chips = kit.chips(Adjust.entries.map(::adjustLabel), Adjust.entries.indexOf(a)) { vm.selectAdjust(Adjust.entries[it]) }
        return { st ->
            set(st.adjustValues[a] ?: a.default)
            chips.forEachIndexed { i, chip -> chip.isChecked = Adjust.entries[i] == st.selectedAdjust }
        }
    }

    private fun adjustLabel(a: Adjust): String = getString(
        when (a) {
            Adjust.Brightness -> R.string.ie_adj_brightness
            Adjust.Contrast -> R.string.ie_adj_contrast
            Adjust.Saturation -> R.string.ie_adj_saturation
            Adjust.Exposure -> R.string.ie_adj_exposure
            Adjust.Highlights -> R.string.ie_adj_highlights
            Adjust.Shadows -> R.string.ie_adj_shadows
            Adjust.Temperature -> R.string.ie_adj_temperature
            Adjust.Tint -> R.string.ie_adj_tint
            Adjust.Vibrance -> R.string.ie_adj_vibrance
            Adjust.Hue -> R.string.ie_adj_hue
            Adjust.Gamma -> R.string.ie_adj_gamma
            Adjust.Sharpen -> R.string.ie_adj_sharpen
            Adjust.Vignette -> R.string.ie_adj_vignette
        }
    )

    private fun buildFilters(s: ImageEditorViewModel.UiState): (ImageEditorViewModel.UiState) -> Unit {
        val ctx = requireContext()
        var intensity: (Float) -> Unit = {}
        if (s.selectedFilter != null) {
            intensity = kit.slider(getString(R.string.ie_intensity), 0f, 100f, s.filterIntensity * 100f, onReset = { vm.setFilterIntensity(1f) }) {
                vm.setFilterIntensity(it / 100f)
            }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tiles = mutableListOf<Pair<FilterPreset?, View>>()
        fun tile(label: String, bmp: android.graphics.Bitmap?, preset: FilterPreset?) {
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
                setBackgroundResource(R.drawable.bg_rounded)
                setOnClickListener { vm.selectFilter(preset) }
            }
            col.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
                clipToOutline = true
                setBackgroundResource(R.drawable.bg_rounded)
            }, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)))
            col.addView(TextView(ctx).apply { text = label; textSize = 11f; setTextColor(ctx.color(R.color.app_text)) })
            row.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(6) })
            tiles += preset to col
        }
        tile(getString(R.string.ie_filter_none), s.filterSource, null)
        FilterPreset.entries.forEach { p -> tile(p.label, s.filterThumbs[p], p) }
        kit.view(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        val accent = ctx.color(R.color.accent_blue)
        return { st ->
            intensity(st.filterIntensity * 100f)
            tiles.forEach { (p, v) ->
                v.backgroundTintList = ColorStateList.valueOf(if (p == st.selectedFilter) accent else android.graphics.Color.TRANSPARENT)
            }
        }
    }

    private fun buildDraw(s: ImageEditorViewModel.UiState): (ImageEditorViewModel.UiState) -> Unit {
        val ctx = requireContext()
        val b = s.brush
        val brushes = listOf(
            BrushKind.Pen to (R.drawable.ic_edit to R.string.ie_brush_pen),
            BrushKind.Highlighter to (R.drawable.ic_border_color to R.string.ie_brush_highlighter),
            BrushKind.Neon to (R.drawable.ic_flare to R.string.ie_brush_neon),
            BrushKind.Eraser to (R.drawable.ic_auto_fix_normal to R.string.ie_brush_eraser),
            BrushKind.Blur to (R.drawable.ic_blur_on to R.string.ie_brush_blur),
            BrushKind.Pixelate to (R.drawable.ic_grid_on to R.string.ie_brush_pixelate)
        )
        val brushButtons = kit.iconRow(brushes.map { (k, v) -> Triple(v.first, getString(v.second)) { vm.updateBrush { it.copy(brush = k) } } })
        var shapeButtons = emptyList<com.google.android.material.button.MaterialButton>()
        val shapes = listOf(
            ShapeKind.Free to R.drawable.ic_gesture, ShapeKind.Line to R.drawable.ic_horizontal_rule,
            ShapeKind.LineArrow to R.drawable.ic_arrow_right_alt, ShapeKind.DoubleLineArrow to R.drawable.ic_swap_horiz,
            ShapeKind.Arrow to R.drawable.ic_call_made, ShapeKind.DoubleArrow to R.drawable.ic_sync_alt,
            ShapeKind.OutlinedRect to R.drawable.ic_crop_square, ShapeKind.Rect to R.drawable.ic_square,
            ShapeKind.OutlinedOval to R.drawable.ic_radio_button_unchecked, ShapeKind.Oval to R.drawable.ic_circle,
            ShapeKind.OutlinedTriangle to R.drawable.ic_change_history, ShapeKind.OutlinedPolygon to R.drawable.ic_hexagon,
            ShapeKind.OutlinedStar to R.drawable.ic_star_outline, ShapeKind.Star to R.drawable.ic_star,
            ShapeKind.Lasso to R.drawable.ic_draw, ShapeKind.FloodFill to R.drawable.ic_format_color_fill,
            ShapeKind.Spray to R.drawable.ic_grain
        )
        if (b.brush != BrushKind.Eraser) {
            shapeButtons = kit.iconRow(shapes.map { (k, icon) -> Triple(icon, k.name) { vm.updateBrush { it.copy(shape = k) } } }, scroll = true)
        }
        val colorable = b.brush == BrushKind.Pen || b.brush == BrushKind.Highlighter || b.brush == BrushKind.Neon
        var swatch: View? = null
        if (colorable) {
            swatch = kit.button(getString(R.string.ie_color), R.drawable.ic_format_color_fill, primary = false) {
                PanelKit.pickColor(ctx, getString(R.string.ie_color)) { c -> vm.updateBrush { it.copy(color = c) } }
            }
        }
        val size = if (b.shape == ShapeKind.FloodFill && b.brush != BrushKind.Eraser) {
            kit.slider(getString(R.string.ie_tolerance), 0f, 100f, b.tolerance * 100f) { v -> vm.updateBrush { it.copy(tolerance = v / 100f) } }
        } else {
            kit.slider(getString(R.string.ie_size), 2f, 120f, b.width * 1000f) { v -> vm.updateBrush { it.copy(width = v / 1000f) } }
        }
        var strength: (Float) -> Unit = {}
        var opacity: (Float) -> Unit = {}
        var softness: (Float) -> Unit = {}
        when (b.brush) {
            BrushKind.Blur, BrushKind.Pixelate -> strength = kit.slider(getString(R.string.ie_strength), 5f, 100f, b.effectStrength * 1000f) { v ->
                vm.updateBrush { it.copy(effectStrength = v / 1000f) }
            }
            BrushKind.Pen, BrushKind.Neon -> {
                opacity = kit.slider(getString(R.string.ie_opacity), 5f, 100f, b.alpha * 100f, format = { "${it.roundToInt()}%" }) { v -> vm.updateBrush { it.copy(alpha = v / 100f) } }
                softness = kit.slider(getString(R.string.ie_softness), 0f, 100f, b.softness * 100f) { v -> vm.updateBrush { it.copy(softness = v / 100f) } }
            }
            else -> Unit
        }
        return { st ->
            val br = st.brush
            brushes.forEachIndexed { i, (k, _) -> kit.mark(brushButtons[i], br.brush == k) }
            shapeButtons.forEachIndexed { i, btn -> kit.mark(btn, br.shape == shapes[i].first, ctx.color(R.color.accent_purple)) }
            (swatch as? com.google.android.material.button.MaterialButton)?.iconTint = ColorStateList.valueOf(br.color or 0xFF000000.toInt())
            size(if (br.shape == ShapeKind.FloodFill && br.brush != BrushKind.Eraser) br.tolerance * 100f else br.width * 1000f)
            strength(br.effectStrength * 1000f)
            opacity(br.alpha * 100f)
            softness(br.softness * 100f)
        }
    }

    private fun buildText(s: ImageEditorViewModel.UiState): (ImageEditorViewModel.UiState) -> Unit {
        val ctx = requireContext()
        val field = layoutInflater.inflate(R.layout.view_text_field, binding.panel, false) as TextInputLayout
        field.hint = getString(R.string.ie_text_hint)
        val input = field.findViewById<TextInputEditText>(R.id.input)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setText(s.text.text)
        input.doAfterTextChanged { e -> val t = e?.toString().orEmpty(); if (t != state.text.text) vm.updateText { it.copy(text = t) } }
        kit.view(field, 0)
        kit.button(getString(R.string.ie_text_add)) { vm.applyText() }
        val fonts = kit.chips(TextFont.entries.map { it.name } + listOf(getString(R.string.ie_bold), getString(R.string.ie_italic)), TextFont.entries.indexOf(s.text.font)) { i ->
            when {
                i < TextFont.entries.size -> vm.updateText { it.copy(font = TextFont.entries[i]) }
                i == TextFont.entries.size -> vm.updateText { it.copy(bold = !it.bold) }
                else -> vm.updateText { it.copy(italic = !it.italic) }
            }
        }
        val colors = kit.iconRow(listOf(
            Triple(R.drawable.ic_format_color_text, getString(R.string.ie_color)) {
                PanelKit.pickColor(ctx, getString(R.string.ie_color)) { c -> vm.updateText { it.copy(color = c) } }
            },
            Triple(R.drawable.ic_border_color, getString(R.string.ie_outline)) {
                PanelKit.pickColor(ctx, getString(R.string.ie_outline), getString(R.string.viewer_clear)) { c -> vm.updateText { it.copy(outlineColor = c) } }
            },
            Triple(R.drawable.ic_format_color_fill, getString(R.string.ie_background)) {
                PanelKit.pickColor(ctx, getString(R.string.ie_background), getString(R.string.viewer_clear)) { c -> vm.updateText { it.copy(backgroundColor = c) } }
            }
        ))
        val opacity = kit.slider(getString(R.string.ie_opacity), 10f, 100f, s.text.alpha * 100f, format = { "${it.roundToInt()}%" }) { v ->
            vm.updateText { it.copy(alpha = v / 100f) }
        }
        return { st ->
            val t = st.text
            if (input.text.toString() != t.text) input.setText(t.text)
            fonts.forEachIndexed { i, chip ->
                chip.isChecked = when {
                    i < TextFont.entries.size -> TextFont.entries[i] == t.font
                    i == TextFont.entries.size -> t.bold
                    else -> t.italic
                }
            }
            colors[0].iconTint = ColorStateList.valueOf(t.color or 0xFF000000.toInt())
            opacity(t.alpha * 100f)
        }
    }

    private fun buildMore(): (ImageEditorViewModel.UiState) -> Unit {
        kit.iconRow(listOf(
            Triple(R.drawable.ic_auto_fix_high, getString(R.string.ie_more_background)) { vm.openMore(MoreTool.Background) },
            Triple(R.drawable.ic_border_color, getString(R.string.ie_more_watermark)) { showWatermark() },
            Triple(R.drawable.ic_straighten, getString(R.string.ie_more_resize)) { showResize() },
            Triple(R.drawable.ic_image, getString(R.string.ie_format)) { showExport() },
            Triple(R.drawable.ic_info, getString(R.string.ie_more_exif)) { showExif() }
        ))
        kit.text("${getString(R.string.ie_more_background)} · ${getString(R.string.ie_more_watermark)} · ${getString(R.string.ie_more_resize)} · ${getString(R.string.ie_format)} · ${getString(R.string.ie_more_exif)}", 11f)
            .gravity = android.view.Gravity.CENTER
        return {}
    }

    private fun buildBackground(s: ImageEditorViewModel.UiState): (ImageEditorViewModel.UiState) -> Unit {
        if (s.bgAvailable) {
            kit.button(getString(if (s.bgBusy) R.string.ie_bg_working else R.string.ie_bg_auto), R.drawable.ic_auto_fix_high) { vm.autoRemoveBackground() }
                .isEnabled = !s.bgBusy
        } else kit.text(getString(R.string.ie_bg_unavailable))
        val mode = kit.chips(listOf(getString(R.string.ie_bg_erase), getString(R.string.ie_bg_restore)), if (s.bgRestore) 1 else 0) { vm.setBgRestore(it == 1) }
        val size = kit.slider(getString(R.string.ie_size), 10f, 200f, s.bgBrushWidth * 1000f) { v -> vm.setBgBrushWidth(v / 1000f) }
        kit.button(getString(R.string.ie_done), primary = false) { vm.closeMore() }
        return { st ->
            mode[0].isChecked = !st.bgRestore
            mode[1].isChecked = st.bgRestore
            size(st.bgBrushWidth * 1000f)
        }
    }

    // ── Sheets ────────────────────────────────────────────────────────────────────────────────

    /** A bottom sheet whose content is built with a [PanelKit]. */
    private fun sheet(title: String, build: (PanelKit, BottomSheetDialog) -> Unit) {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ctx.dp(20)
            setPadding(pad, ctx.dp(4), pad, ctx.dp(24))
        }
        column.addView(TextView(ctx).apply {
            text = title
            setTextAppearance(R.style.TextAppearance_ClearPDF_SectionTitle)
        })
        build(PanelKit(ctx, column), dialog)
        dialog.setContentView(androidx.core.widget.NestedScrollView(ctx).apply { addView(column) })
        dialog.show()
    }

    private fun showWatermark() = sheet(getString(R.string.ie_more_watermark)) { k, dialog ->
        val ctx = requireContext()
        val p = state.watermark
        val field = layoutInflater.inflate(R.layout.view_text_field, null) as TextInputLayout
        field.hint = getString(R.string.ie_wm_text)
        val input = field.findViewById<TextInputEditText>(R.id.input)
        input.setText(p.text)
        input.doAfterTextChanged { e -> vm.updateWatermark { it.copy(text = e?.toString().orEmpty()) } }
        k.view(field)
        k.iconRow(listOf(
            Triple(R.drawable.ic_format_color_text, getString(R.string.ie_color)) {
                PanelKit.pickColor(ctx, getString(R.string.ie_color)) { c -> vm.updateWatermark { it.copy(color = c) } }
            },
            Triple(R.drawable.ic_image, getString(R.string.ie_wm_image)) { watermarkImage.launch("image/*") },
            Triple(R.drawable.ic_close, getString(R.string.ie_wm_clear_image)) { vm.updateWatermark { it.copy(image = null) } }
        ))
        k.text(getString(R.string.ie_wm_position))
        k.chips(WatermarkPosition.entries.map { pos ->
            if (pos == WatermarkPosition.Tiled) getString(R.string.ie_wm_tiled) else pos.name.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        }, WatermarkPosition.entries.indexOf(p.position)) { i -> vm.updateWatermark { it.copy(position = WatermarkPosition.entries[i]) } }
        k.slider(getString(R.string.ie_size), 15f, 200f, p.size * 1000f) { v -> vm.updateWatermark { it.copy(size = v / 1000f) } }
        k.slider(getString(R.string.ie_opacity), 5f, 100f, p.alpha * 100f, format = { "${it.roundToInt()}%" }) { v -> vm.updateWatermark { it.copy(alpha = v / 100f) } }
        k.slider(getString(R.string.ie_rotation), -90f, 90f, p.rotation, format = { "${it.roundToInt()}°" }) { v -> vm.updateWatermark { it.copy(rotation = v) } }
        k.button(getString(R.string.ie_apply)) { vm.applyWatermark(); dialog.dismiss() }
    }

    private fun showResize() = sheet(getString(R.string.ie_more_resize)) { k, dialog ->
        val s = state
        k.text(getString(R.string.ie_current_size, s.fullWidth, s.fullHeight), 13f)
        fun numberField(hint: String, value: Int): TextInputEditText {
            val f = layoutInflater.inflate(R.layout.view_text_field, null) as TextInputLayout
            f.hint = hint
            val e = f.findViewById<TextInputEditText>(R.id.input)
            e.inputType = InputType.TYPE_CLASS_NUMBER
            e.setText(value.toString())
            k.view(f)
            return e
        }
        val w = numberField(getString(R.string.ie_width), s.fullWidth)
        val h = numberField(getString(R.string.ie_height), s.fullHeight)
        var keep = true
        val ratio = if (s.fullHeight > 0) s.fullWidth / s.fullHeight.toFloat() else 1f
        var syncing = false
        k.chips(listOf(getString(R.string.ie_keep_ratio)), 0) { keep = !keep }
        w.doAfterTextChanged { e ->
            if (syncing || !keep) return@doAfterTextChanged
            val v = e?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            syncing = true; h.setText((v / ratio).roundToInt().toString()); syncing = false
        }
        h.doAfterTextChanged { e ->
            if (syncing || !keep) return@doAfterTextChanged
            val v = e?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            syncing = true; w.setText((v * ratio).roundToInt().toString()); syncing = false
        }
        k.button(getString(R.string.ie_apply)) {
            vm.applyResize(w.text?.toString()?.toIntOrNull() ?: 0, h.text?.toString()?.toIntOrNull() ?: 0)
            dialog.dismiss()
        }
    }

    private fun metadataChips(k: PanelKit) {
        k.text(getString(R.string.ie_metadata))
        k.chips(
            listOf(getString(R.string.ie_meta_keep), getString(R.string.ie_meta_no_location), getString(R.string.ie_meta_none)),
            state.export.metadata.ordinal
        ) { i -> vm.updateExport { it.copy(metadata = MetadataMode.entries[i]) } }
    }

    private fun showExport() = sheet(getString(R.string.ie_more_export)) { k, dialog ->
        val e = state.export
        k.text(getString(R.string.ie_format))
        k.chips(ExportFormat.entries.map { it.label }, e.format.ordinal) { i -> vm.updateExport { it.copy(format = ExportFormat.entries[i]) } }
        k.slider(getString(R.string.ie_quality), 30f, 100f, e.quality.toFloat()) { v -> vm.updateExport { it.copy(quality = v.roundToInt()) } }
        metadataChips(k)
        k.button(getString(R.string.ie_save), R.drawable.ic_save_alt) { dialog.dismiss(); vm.saveToGallery() }
        k.button(getString(R.string.ie_share), R.drawable.ic_ios_share, primary = false) {
            dialog.dismiss()
            vm.share { uri, mime ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivity(Intent.createChooser(send, null)) }
            }
        }
        k.button(getString(R.string.ie_export_pdf), R.drawable.ic_picture_as_pdf, primary = false) {
            dialog.dismiss()
            vm.exportToPdf { uri -> uri?.let { AppNavigator.openPdfViewer(findNavController(), it) } }
        }
    }

    private fun showExif() = sheet(getString(R.string.ie_more_exif)) { k, _ ->
        if (state.exif.isEmpty()) k.text(getString(R.string.ie_exif_empty), 14f)
        state.exif.forEach { entry -> k.text("${entry.label}: ${entry.value}", 13f) }
        metadataChips(k)
    }

    private companion object {
        /** Aspect presets: ratio null = free, −1 = the image's own ratio. */
        val CropAspects: List<Pair<String, Float?>> = listOf(
            "Free" to null, "Original" to -1f, "1:1" to 1f, "4:5" to 4f / 5f, "4:3" to 4f / 3f,
            "3:4" to 3f / 4f, "3:2" to 3f / 2f, "16:9" to 16f / 9f, "9:16" to 9f / 16f
        )

        /** Mask presets: index 0 is a plain rectangle. */
        val CropMasks: List<Pair<String, CropMask?>> = listOf(
            "Rectangle" to null, "Rounded" to CropMask.Rounded, "Circle" to CropMask.Circle,
            "Cut corner" to CropMask.CutCorner, "Hexagon" to CropMask.Hexagon,
            "Heart" to CropMask.Heart, "Star" to CropMask.Star
        )
    }
}
