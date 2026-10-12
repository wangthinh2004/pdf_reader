package com.chethan616.clearpdf.ui.fragment.home

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.AppSettingsManager
import com.chethan616.clearpdf.data.repository.DeviceDocument
import com.chethan616.clearpdf.data.repository.RecentFile
import com.chethan616.clearpdf.data.repository.RecentFilesManager
import com.chethan616.clearpdf.data.repository.StorageAccess
import com.chethan616.clearpdf.databinding.DialogFileInfoBinding
import com.chethan616.clearpdf.databinding.FragmentHomeBinding
import com.chethan616.clearpdf.ui.common.ActionSheet
import com.chethan616.clearpdf.ui.common.AppNavigator
import com.chethan616.clearpdf.ui.common.DocumentActions
import com.chethan616.clearpdf.ui.common.SearchHeader
import com.chethan616.clearpdf.ui.common.SheetAction
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.applySystemBarPadding
import com.chethan616.clearpdf.ui.common.collectWhileStarted
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.formatFileSize
import com.chethan616.clearpdf.ui.common.formatTimestamp
import com.chethan616.clearpdf.ui.common.kindVisual
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.viewmodel.DeviceDocsState
import com.chethan616.clearpdf.ui.viewmodel.DeviceDocumentsViewModel
import com.chethan616.clearpdf.utils.DocKind
import com.chethan616.clearpdf.utils.docKindOf
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home tab: welcome card, recent files and every document found on the device. */
class HomeFragment : Fragment(), HomeListener {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // Activity-scoped so the scanned list survives switching between the main tabs.
    private val deviceDocsVm: DeviceDocumentsViewModel by activityViewModels()

    private lateinit var adapter: HomeAdapter
    private lateinit var search: SearchHeader

    private var recents: List<RecentFile> = emptyList()
    private var recentFilesEnabled = true
    private var showAllRecents = false
    private var query = ""
    private var recentFilter: DocKind? = null
    private var deviceTab: DocKind? = null
    private var deviceState: DeviceDocsState = DeviceDocsState.Loading

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
        deviceDocsVm.refresh()
    }
    private val legacyStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        deviceDocsVm.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceTab = savedInstanceState?.getString(KEY_DEVICE_TAB)?.let { name -> DocKind.entries.firstOrNull { it.name == name } }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.root.applySystemBarPadding(top = true)

        search = SearchHeader(
            binding.header,
            title = getString(R.string.home_title),
            hint = getString(R.string.recents_search_hint),
            onQueryChange = { query = it; render() },
            onActiveChange = { render() }
        )
        binding.header.title.typeface = androidx.core.content.res.ResourcesCompat.getFont(requireContext(), R.font.sour_gummy_bold)

        adapter = HomeAdapter(this)
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.list.itemAnimator?.changeDuration = 120
        ItemTouchHelper(SwipeToRemove()).attachToRecyclerView(binding.list)

        collectWhileStarted(deviceDocsVm.state) {
            deviceState = it
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        recentFilesEnabled = AppSettingsManager.getRememberRecentFiles(ctx)
        recents = RecentFilesManager.getRecents(ctx)
        deviceDocsVm.refresh()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DEVICE_TAB, deviceTab?.name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── List ──────────────────────────────────────────────────────────────────────────────────

    private fun render() {
        if (_binding == null) return
        val items = mutableListOf<HomeItem>()
        val searching = query.isNotBlank()
        if (!search.active) items += HomeItem.Hero

        if (recentFilesEnabled) {
            val byKind = recentFilter?.let { kind -> recents.filter { docKindOf(it.name) == kind } } ?: recents
            val filtered = if (!searching) byKind else byKind.filter { it.name.contains(query.trim(), ignoreCase = true) }
            val visible = if (showAllRecents || searching) filtered else filtered.take(HOME_RECENT_LIMIT)
            val seeAll = when {
                filtered.size <= HOME_RECENT_LIMIT -> null
                showAllRecents -> getString(R.string.home_see_less)
                else -> "${getString(R.string.home_see_all)} (${filtered.size})"
            }
            items += HomeItem.RecentsHeader(
                hasRecents = recents.isNotEmpty(),
                noMatches = recents.isNotEmpty() && filtered.isEmpty(),
                filterActive = recentFilter != null,
                seeAllLabel = seeAll
            )
            visible.forEach { items += HomeItem.Recent(it) }
            items += HomeItem.RecentsFooter(
                showHint = !searching && filtered.size > HOME_RECENT_LIMIT && !showAllRecents
            )
        }

        val state = deviceState
        if (state !is DeviceDocsState.Unsupported) {
            val docs = if (state is DeviceDocsState.Ready) {
                val q = query.trim()
                state.docs.filter { (deviceTab == null || it.kind == deviceTab) && (q.isEmpty() || it.name.contains(q, ignoreCase = true)) }
            } else emptyList()
            items += HomeItem.DeviceHeader(state, deviceTab, headerOnly = docs.isEmpty())
            docs.forEachIndexed { i, doc -> items += HomeItem.DeviceDoc(doc, last = i == docs.lastIndex) }
        }
        adapter.submitList(items)
    }

    // ── HomeListener ──────────────────────────────────────────────────────────────────────────

    override fun onOpenDocument() {
        runCatching { openDocument.launch(AppNavigator.OPEN_DOC_MIMES) }
            .onFailure { AppNavigator.openPdfViewer(findNavController()) }
    }

    override fun onScan() = AppNavigator.open(findNavController(), R.id.scan)

    override fun onFilterClick(anchor: View) {
        val options = listOf(
            null to R.string.recents_filter_all,
            DocKind.Pdf to R.string.recents_filter_pdf,
            DocKind.Word to R.string.recents_filter_word,
            DocKind.Excel to R.string.recents_filter_excel,
            DocKind.Ppt to R.string.recents_filter_ppt,
            DocKind.Hwp to R.string.recents_filter_hwp,
            DocKind.Txt to R.string.recents_filter_txt,
            DocKind.Image to R.string.recents_filter_image
        )
        val popup = PopupMenu(requireContext(), anchor)
        options.forEachIndexed { i, (kind, label) ->
            popup.menu.add(0, i, i, label).apply {
                isCheckable = true
                isChecked = kind == recentFilter
            }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            recentFilter = options[item.itemId].first
            showAllRecents = false
            render()
            true
        }
        popup.show()
    }

    override fun onToggleSeeAll() {
        showAllRecents = !showAllRecents
        render()
    }

    override fun onClearRecents() {
        RecentFilesManager.clearRecents(requireContext())
        recents = emptyList()
        showAllRecents = false
        render()
    }

    override fun onRecentClick(file: RecentFile) =
        AppNavigator.openDocument(findNavController(), requireContext(), file.uri, file.name)

    override fun onRecentLongClick(file: RecentFile) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val canDelete = withContext(Dispatchers.IO) { DocumentActions.canDelete(ctx, file.uri) }
            val actions = buildList {
                add(SheetAction(R.drawable.ic_file_open, getString(R.string.recents_open), ctx.color(R.color.accent_blue)) {
                    onRecentClick(file)
                })
                add(SheetAction(R.drawable.ic_ios_share, getString(R.string.recents_share), ctx.color(R.color.accent_green)) {
                    DocumentActions.share(ctx, file.uri)
                })
                add(SheetAction(
                    if (file.pinned) R.drawable.ic_push_pin else R.drawable.ic_push_pin_outlined,
                    getString(if (file.pinned) R.string.recents_unpin else R.string.recents_pin),
                    ctx.color(R.color.accent_orange)
                ) {
                    RecentFilesManager.togglePin(ctx, file.uri)
                    recents = RecentFilesManager.getRecents(ctx)
                    render()
                })
                add(SheetAction(R.drawable.ic_open_in_new, getString(R.string.recents_open_with), ctx.color(R.color.accent_indigo)) {
                    DocumentActions.openWith(ctx, file.uri)
                })
                add(SheetAction(R.drawable.ic_info, getString(R.string.recents_details), ctx.color(R.color.accent_teal)) {
                    showFileInfo(file)
                })
                add(SheetAction(
                    R.drawable.ic_remove_circle_outline, getString(R.string.recents_remove_from_list),
                    ctx.color(R.color.accent_red), destructive = true
                ) { removeRecent(file) })
                if (canDelete) add(SheetAction(
                    R.drawable.ic_delete_forever, getString(R.string.recents_delete_file),
                    ctx.color(R.color.accent_red), destructive = true
                ) { confirmDelete(file) })
            }
            if (_binding != null) ActionSheet.show(ctx, file.name, actions)
        }
    }

    override fun onGrantDeviceAccess() {
        if (StorageAccess.needsSettingsScreen()) {
            runCatching { allFilesSettings.launch(StorageAccess.settingsIntent(requireContext())) }
        } else {
            legacyStorage.launch(StorageAccess.LEGACY_PERMISSION)
        }
    }

    override fun onRetryDeviceDocs() = deviceDocsVm.refresh()

    override fun onDeviceTab(kind: DocKind?) {
        if (deviceTab == kind) return
        deviceTab = kind
        render()
    }

    override fun onDeviceDocClick(doc: DeviceDocument) =
        AppNavigator.openDocument(findNavController(), requireContext(), doc.uri, doc.name)

    // ── Recents actions ───────────────────────────────────────────────────────────────────────

    private fun removeRecent(file: RecentFile) {
        val ctx = requireContext().applicationContext
        recents = recents.filterNot { it.uriString == file.uriString }
        render()
        lifecycleScope.launch(Dispatchers.IO) { RecentFilesManager.removeRecent(ctx, file.uri) }
    }

    private fun confirmDelete(file: RecentFile) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.recents_delete_file)
            .setMessage(file.name)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.recents_delete_file) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { DocumentActions.delete(ctx, file.uri) }
                    if (ok) removeRecent(file)
                    else Toast.makeText(ctx, R.string.recents_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showFileInfo(file: RecentFile) {
        val ctx = requireContext()
        val kind = docKindOf(file.name)
        val visual = kindVisual(kind)
        val info = DialogFileInfoBinding.inflate(layoutInflater)
        val kindColor = ctx.color(visual.color)
        info.icon.setImageResource(visual.icon)
        info.icon.tint(kindColor)
        info.iconTile.tintBackground(ctx.accentWash(kindColor))
        info.name.text = file.name
        val unknown = getString(R.string.recents_unknown)
        listOf(
            getString(if (kind == DocKind.Excel) R.string.recents_sheets else R.string.recents_pages) to
                (if (file.pageCount > 0) file.pageCount.toString() else unknown) to 1,
            getString(R.string.recents_size_label) to
                (if (file.sizeBytes > 0) formatFileSize(ctx, file.sizeBytes) else unknown) to 1,
            getString(R.string.recents_added) to formatTimestamp(ctx, file.timestamp) to 1,
            getString(R.string.recents_location) to file.uri.toString() to 3
        ).forEach { (pair, maxLines) -> info.rows.addView(infoRow(pair.first, pair.second, maxLines)) }
        MaterialAlertDialogBuilder(ctx)
            .setView(info.root)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun infoRow(label: String, value: String, maxLines: Int): View {
        val ctx = requireContext()
        return android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, ctx.dp(10), 0, 0)
            addView(android.widget.TextView(ctx).apply {
                text = label.uppercase()
                textSize = 10f
                letterSpacing = 0.08f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.color(R.color.app_text_secondary))
            })
            addView(android.widget.TextView(ctx).apply {
                text = value
                textSize = 13f
                this.maxLines = maxLines
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ctx.color(R.color.app_text))
            })
        }
    }

    /** Swipe an unpinned recents row left to drop it from the list. */
    private inner class SwipeToRemove : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete_outline)!!.mutate()

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val pos = viewHolder.bindingAdapterPosition
            return if (pos != RecyclerView.NO_POSITION && adapter.isSwipeable(pos)) ItemTouchHelper.LEFT else 0
        }

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.4f

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val file = adapter.recentAt(viewHolder.bindingAdapterPosition) ?: return
            removeRecent(file)
        }

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            if (dX < 0f) {
                val item = viewHolder.itemView
                val ctx = item.context
                val inset = ctx.dp(12f)
                val rect = RectF(item.left + inset, item.top.toFloat(), item.right - inset, item.bottom - ctx.dp(8f))
                paint.color = ctx.color(R.color.kind_pdf)
                paint.alpha = ((-dX / (item.width * 0.25f)).coerceIn(0.3f, 1f) * 255).toInt()
                c.drawRoundRect(rect, ctx.dp(12f), ctx.dp(12f), paint)
                val size = ctx.dp(22)
                val right = (rect.right - ctx.dp(20f)).toInt()
                val top = (rect.centerY() - size / 2f).toInt()
                icon.setBounds(right - size, top, right, top + size)
                icon.setTint(android.graphics.Color.WHITE)
                icon.draw(c)
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private companion object {
        const val HOME_RECENT_LIMIT = 5
        const val KEY_DEVICE_TAB = "device_tab"
    }
}
