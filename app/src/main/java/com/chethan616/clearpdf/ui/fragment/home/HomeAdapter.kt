package com.chethan616.clearpdf.ui.fragment.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.data.repository.DeviceDocument
import com.chethan616.clearpdf.data.repository.RecentFile
import com.chethan616.clearpdf.databinding.ItemDeviceDocBinding
import com.chethan616.clearpdf.databinding.ItemDeviceHeaderBinding
import com.chethan616.clearpdf.databinding.ItemHomeHeroBinding
import com.chethan616.clearpdf.databinding.ItemRecentRowBinding
import com.chethan616.clearpdf.databinding.ItemRecentsFooterBinding
import com.chethan616.clearpdf.databinding.ItemRecentsHeaderBinding
import com.chethan616.clearpdf.databinding.ViewDocumentRowBinding
import com.chethan616.clearpdf.ui.common.accentWash
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.formatFileSize
import com.chethan616.clearpdf.ui.common.formatTimestamp
import com.chethan616.clearpdf.ui.common.kindVisual
import com.chethan616.clearpdf.ui.common.tint
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.common.withAlpha
import com.chethan616.clearpdf.ui.viewmodel.DeviceDocsState
import com.chethan616.clearpdf.utils.DocKind
import com.chethan616.clearpdf.utils.docKindOf
import com.google.android.material.chip.Chip

/** Rows of the Home list. Recents and device documents each render as one card split into rows. */
sealed class HomeItem(val key: String) {
    data object Hero : HomeItem("hero")

    data class RecentsHeader(
        val hasRecents: Boolean,
        val noMatches: Boolean,
        val filterActive: Boolean,
        /** Null hides the see-all toggle. */
        val seeAllLabel: String?
    ) : HomeItem("recentsHeader")

    data class Recent(val file: RecentFile) : HomeItem("recent:" + file.uriString)

    data class RecentsFooter(val showHint: Boolean) : HomeItem("recentsFooter")

    data class DeviceHeader(
        val state: DeviceDocsState,
        val tab: DocKind?,
        /** True when no document rows follow, so the header closes the card itself. */
        val headerOnly: Boolean
    ) : HomeItem("deviceHeader")

    data class DeviceDoc(val doc: DeviceDocument, val last: Boolean) : HomeItem("dev:" + doc.uri)
}

interface HomeListener {
    fun onOpenDocument()
    fun onScan()
    fun onFilterClick(anchor: View)
    fun onToggleSeeAll()
    fun onClearRecents()
    fun onRecentClick(file: RecentFile)
    fun onRecentLongClick(file: RecentFile)
    fun onGrantDeviceAccess()
    fun onRetryDeviceDocs()
    fun onDeviceTab(kind: DocKind?)
    fun onDeviceDocClick(doc: DeviceDocument)
}

class HomeAdapter(private val listener: HomeListener) :
    ListAdapter<HomeItem, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        HomeItem.Hero -> TYPE_HERO
        is HomeItem.RecentsHeader -> TYPE_RECENTS_HEADER
        is HomeItem.Recent -> TYPE_RECENT
        is HomeItem.RecentsFooter -> TYPE_RECENTS_FOOTER
        is HomeItem.DeviceHeader -> TYPE_DEVICE_HEADER
        is HomeItem.DeviceDoc -> TYPE_DEVICE_DOC
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroHolder(ItemHomeHeroBinding.inflate(inflater, parent, false))
            TYPE_RECENTS_HEADER -> RecentsHeaderHolder(ItemRecentsHeaderBinding.inflate(inflater, parent, false))
            TYPE_RECENT -> RecentHolder(ItemRecentRowBinding.inflate(inflater, parent, false))
            TYPE_RECENTS_FOOTER -> FooterHolder(ItemRecentsFooterBinding.inflate(inflater, parent, false))
            TYPE_DEVICE_HEADER -> DeviceHeaderHolder(ItemDeviceHeaderBinding.inflate(inflater, parent, false))
            else -> DeviceDocHolder(ItemDeviceDocBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            HomeItem.Hero -> Unit
            is HomeItem.RecentsHeader -> (holder as RecentsHeaderHolder).bind(item)
            is HomeItem.Recent -> (holder as RecentHolder).bind(item.file)
            is HomeItem.RecentsFooter -> (holder as FooterHolder).bind(item)
            is HomeItem.DeviceHeader -> (holder as DeviceHeaderHolder).bind(item)
            is HomeItem.DeviceDoc -> (holder as DeviceDocHolder).bind(item)
        }
    }

    /** True for rows that swipe away (unpinned recents). Pinned entries are an explicit "keep". */
    fun isSwipeable(position: Int): Boolean =
        (getItem(position) as? HomeItem.Recent)?.file?.pinned == false

    fun recentAt(position: Int): RecentFile? = (getItem(position) as? HomeItem.Recent)?.file

    // ── Holders ───────────────────────────────────────────────────────────────────────────────

    private inner class HeroHolder(b: ItemHomeHeroBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            val ctx = b.root.context
            val accent = ctx.color(R.color.tool_open)
            b.heroIconTile.tintBackground(ctx.accentWash(accent, 0.14f, 0.25f))
            b.onDeviceBadge.tintBackground(ctx.accentWash(accent, 0.12f, 0.20f))
            b.openButton.setOnClickListener { listener.onOpenDocument() }
            b.scanButton.setOnClickListener { listener.onScan() }
        }
    }

    private inner class RecentsHeaderHolder(private val b: ItemRecentsHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.filterButton.setOnClickListener { listener.onFilterClick(it) }
            b.seeAll.setOnClickListener { listener.onToggleSeeAll() }
            b.clearButton.setOnClickListener { listener.onClearRecents() }
        }

        fun bind(item: HomeItem.RecentsHeader) {
            val ctx = b.root.context
            val accent = ctx.color(R.color.tool_open)
            b.filterButton.isVisible = item.hasRecents
            b.filterButton.tintBackground(
                if (item.filterActive) withAlpha(accent, 0.20f) else ctx.color(R.color.app_surface_variant)
            )
            b.filterIcon.tint(if (item.filterActive) accent else ctx.color(R.color.app_text_secondary))
            b.seeAll.isVisible = item.seeAllLabel != null
            b.seeAll.text = item.seeAllLabel
            b.clearButton.isVisible = item.hasRecents
            b.empty.isVisible = !item.hasRecents
            b.noMatches.isVisible = item.noMatches
        }
    }

    private inner class RecentHolder(private val b: ItemRecentRowBinding) : RecyclerView.ViewHolder(b.root) {
        private var file: RecentFile? = null

        init {
            b.content.row.setOnClickListener { file?.let(listener::onRecentClick) }
            b.content.row.setOnLongClickListener { file?.let(listener::onRecentLongClick); file != null }
        }

        fun bind(recent: RecentFile) {
            file = recent
            val ctx = b.root.context
            val kind = docKindOf(recent.name)
            val meta = buildString {
                append(formatTimestamp(ctx, recent.timestamp))
                if (recent.sizeBytes > 0) append(" · ").append(formatFileSize(ctx, recent.sizeBytes))
                if (recent.pageCount > 0) {
                    append(" · ")
                    append(
                        if (kind == DocKind.Excel) ctx.getString(R.string.recents_sheet_count, recent.pageCount)
                        else ctx.getString(R.string.recents_page_count, recent.pageCount)
                    )
                }
            }
            bindDocumentRow(b.content, recent.name, meta, kind, showBadge = true, pinned = recent.pinned)
        }
    }

    private class FooterHolder(private val b: ItemRecentsFooterBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: HomeItem.RecentsFooter) {
            b.hint.isVisible = item.showHint
        }
    }

    private inner class DeviceHeaderHolder(private val b: ItemDeviceHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        private val tabs = listOf(
            null to R.string.recents_filter_all,
            DocKind.Pdf to R.string.recents_filter_pdf,
            DocKind.Word to R.string.recents_filter_word,
            DocKind.Excel to R.string.recents_filter_excel,
            DocKind.Ppt to R.string.recents_filter_ppt,
            DocKind.Hwp to R.string.recents_filter_hwp,
            DocKind.Txt to R.string.recents_filter_txt
        )
        private var binding = false

        init {
            val ctx = b.root.context
            tabs.forEachIndexed { index, (_, label) ->
                val chip = (LayoutInflater.from(ctx).inflate(R.layout.view_filter_chip, b.tabs, false) as Chip).apply {
                    id = View.generateViewId()
                    tag = index
                    text = ctx.getString(label)
                }
                b.tabs.addView(chip)
            }
            b.tabs.setOnCheckedStateChangeListener { group, checked ->
                if (binding) return@setOnCheckedStateChangeListener
                val index = checked.firstOrNull()?.let { group.findViewById<View>(it)?.tag as? Int } ?: return@setOnCheckedStateChangeListener
                listener.onDeviceTab(tabs[index].first)
            }
            b.grantButton.setOnClickListener { listener.onGrantDeviceAccess() }
        }

        fun bind(item: HomeItem.DeviceHeader) {
            val ctx = b.root.context
            b.card.setBackgroundResource(if (item.headerOnly) R.drawable.bg_card else R.drawable.bg_card_top)
            b.card.updatePadding(bottom = ctx.dp(if (item.headerOnly) 20 else 8))
            val state = item.state
            b.title.text = if (state is DeviceDocsState.Ready) {
                ctx.getString(R.string.home_device_docs_count, state.docs.size)
            } else ctx.getString(R.string.home_device_docs)

            b.access.isVisible = state is DeviceDocsState.NoAccess
            b.tabsScroll.isVisible = state is DeviceDocsState.Ready
            val message = when (state) {
                DeviceDocsState.Loading -> ctx.getString(R.string.home_device_scanning)
                DeviceDocsState.Error -> ctx.getString(R.string.home_device_error)
                is DeviceDocsState.Ready -> if (item.headerOnly) ctx.getString(R.string.home_device_empty) else null
                else -> null
            }
            b.message.isVisible = message != null
            b.messageText.text = message
            b.spinner.isVisible = state is DeviceDocsState.Loading
            b.message.isClickable = state is DeviceDocsState.Error
            b.message.setOnClickListener(if (state is DeviceDocsState.Error) View.OnClickListener { listener.onRetryDeviceDocs() } else null)

            binding = true
            val index = tabs.indexOfFirst { it.first == item.tab }
            for (i in 0 until b.tabs.childCount) {
                val chip = b.tabs.getChildAt(i) as Chip
                chip.isChecked = chip.tag == index
            }
            binding = false
        }
    }

    private inner class DeviceDocHolder(private val b: ItemDeviceDocBinding) : RecyclerView.ViewHolder(b.root) {
        private var doc: DeviceDocument? = null

        init {
            b.content.row.background = null
            b.content.row.setOnClickListener { doc?.let(listener::onDeviceDocClick) }
        }

        fun bind(item: HomeItem.DeviceDoc) {
            doc = item.doc
            val ctx = b.root.context
            b.card.setBackgroundResource(if (item.last) R.drawable.bg_card_bottom else R.drawable.bg_card_middle)
            b.card.updatePadding(left = ctx.dp(8), right = ctx.dp(8), bottom = ctx.dp(if (item.last) 12 else 0))
            val d = item.doc
            val meta = buildString {
                append(formatFileSize(ctx, d.size)).append(" · ").append(formatTimestamp(ctx, d.modified))
                if (d.folder.isNotEmpty()) append(" · ").append(d.folder)
            }
            bindDocumentRow(b.content, d.name, meta, d.kind, showBadge = false, pinned = false)
        }
    }

    private fun bindDocumentRow(
        row: ViewDocumentRowBinding,
        name: String,
        meta: String,
        kind: DocKind,
        showBadge: Boolean,
        pinned: Boolean
    ) {
        val ctx = row.root.context
        val visual = kindVisual(kind)
        val kindColor = ctx.color(visual.color)
        row.name.text = name
        row.meta.text = meta
        row.icon.setImageResource(visual.icon)
        row.icon.tint(kindColor)
        row.iconTile.tintBackground(ctx.accentWash(kindColor, 0.14f, 0.25f))
        row.badge.isVisible = showBadge
        row.badge.text = visual.label
        row.badge.setTextColor(kindColor)
        row.badge.tintBackground(withAlpha(kindColor, 0.12f))
        row.pinned.isVisible = pinned
        if (pinned) row.pinned.tintBackground(ctx.accentWash(ctx.color(R.color.accent_orange), 0.16f, 0.24f))
    }

    private object Diff : DiffUtil.ItemCallback<HomeItem>() {
        override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem) = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem) = oldItem == newItem
    }

    private companion object {
        const val TYPE_HERO = 0
        const val TYPE_RECENTS_HEADER = 1
        const val TYPE_RECENT = 2
        const val TYPE_RECENTS_FOOTER = 3
        const val TYPE_DEVICE_HEADER = 4
        const val TYPE_DEVICE_DOC = 5
    }
}
