package com.chethan616.clearpdf.presentation.feature.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.core.platform.toUri
import com.chethan616.clearpdf.databinding.ItemDocumentCardBinding
import com.chethan616.clearpdf.databinding.ItemDocumentRowBinding
import com.chethan616.clearpdf.domain.model.LibraryDocument
import com.chethan616.clearpdf.presentation.base.simpleDiff
import com.chethan616.clearpdf.presentation.common.PdfThumbnails
import com.chethan616.clearpdf.presentation.common.formatDocumentDate
import com.chethan616.clearpdf.presentation.common.formatDocumentSize
import com.chethan616.clearpdf.presentation.common.metaLine
import com.chethan616.clearpdf.presentation.common.style
import com.chethan616.clearpdf.utils.DocKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One document in a list, with what the row needs to draw its star and selection. */
data class DocumentItem(
    val document: LibraryDocument,
    val favorite: Boolean,
    val selectionMode: Boolean = false,
    val selected: Boolean = false
)

interface DocumentListener {
    fun onDocumentClick(document: LibraryDocument)
    fun onDocumentLongClick(document: LibraryDocument)
    fun onStarClick(document: LibraryDocument)
    fun onMoreClick(document: LibraryDocument)
}

/**
 * Home's documents as list rows or grid cards ([grid]). Grid cards render the first page of a PDF
 * in [thumbnailScope]; other kinds show their icon on the page.
 */
class DocumentAdapter(
    private val listener: DocumentListener,
    private val thumbnailScope: LifecycleCoroutineScope
) : ListAdapter<DocumentItem, RecyclerView.ViewHolder>(simpleDiff { it.document.ref }) {

    var grid: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun getItemViewType(position: Int) = if (grid) TYPE_CARD else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CARD) {
            CardHolder(ItemDocumentCardBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemDocumentRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is RowHolder -> holder.bind(getItem(position))
            is CardHolder -> holder.bind(getItem(position))
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? CardHolder)?.thumbnailJob?.cancel()
    }

    private fun itemAt(holder: RecyclerView.ViewHolder): DocumentItem? =
        holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)

    private fun View.wire(holder: RecyclerView.ViewHolder, more: View, star: View?) {
        setOnClickListener { itemAt(holder)?.let { listener.onDocumentClick(it.document) } }
        setOnLongClickListener { itemAt(holder)?.let { listener.onDocumentLongClick(it.document) }; true }
        more.setOnClickListener { itemAt(holder)?.let { listener.onMoreClick(it.document) } }
        star?.setOnClickListener { itemAt(holder)?.let { listener.onStarClick(it.document) } }
    }

    private inner class RowHolder(val binding: ItemDocumentRowBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.wire(this, binding.more, binding.star)
        }

        fun bind(item: DocumentItem) = with(binding) {
            val ctx = root.context
            val doc = item.document
            val style = doc.kind.style()
            iconTile.backgroundTintList = ContextCompat.getColorStateList(ctx, style.tile)
            icon.setImageResource(style.icon)
            icon.imageTintList = ContextCompat.getColorStateList(ctx, style.iconTint)
            name.text = doc.name
            meta.text = doc.metaLine(ctx)
            star.setImageResource(if (item.favorite) R.drawable.ic_ds_star_on else R.drawable.ic_ds_star_off)
            star.imageTintList = ContextCompat.getColorStateList(
                ctx, if (item.favorite) R.color.ds_primary else R.color.ds_text_secondary
            )
            star.contentDescription = ctx.getString(if (item.favorite) R.string.doc_unstar else R.string.doc_star)
            actions.isVisible = !item.selectionMode
            check.isVisible = item.selectionMode && item.selected
            root.isActivated = item.selected
        }
    }

    private inner class CardHolder(val binding: ItemDocumentCardBinding) : RecyclerView.ViewHolder(binding.root) {
        var thumbnailJob: Job? = null

        init {
            binding.card.wire(this, binding.more, null)
        }

        fun bind(item: DocumentItem) = with(binding) {
            val ctx = root.context
            val doc = item.document
            val style = doc.kind.style()
            preview.setBackgroundColor(ContextCompat.getColor(ctx, style.tile))
            kindIcon.setImageResource(style.icon)
            kindIcon.imageTintList = ContextCompat.getColorStateList(ctx, style.iconTint)
            name.text = doc.name
            badge.text = style.badge
            badge.setTextColor(ContextCompat.getColor(ctx, style.accent))
            size.text = formatDocumentSize(ctx, doc.sizeBytes)?.let { "· $it" }.orEmpty()
            star.isVisible = item.favorite
            date.text = formatDocumentDate(ctx, doc.modifiedAt)
            more.isVisible = !item.selectionMode
            card.isChecked = item.selected
            bindThumbnail(doc)
        }

        private fun bindThumbnail(doc: LibraryDocument) = with(binding) {
            thumbnailJob?.cancel()
            thumbnail.setImageDrawable(null)
            thumbnail.isVisible = false
            kindIcon.isVisible = true
            if (doc.kind != DocKind.Pdf) return@with
            val uri = doc.ref.toUri()
            val width = root.resources.getDimensionPixelSize(R.dimen.ds_thumbnail_width)
            val show = { bitmap: android.graphics.Bitmap ->
                thumbnail.setImageBitmap(bitmap)
                thumbnail.isVisible = true
                kindIcon.isVisible = false
            }
            PdfThumbnails.cached(uri, width)?.let(show) ?: run {
                thumbnailJob = thumbnailScope.launch {
                    PdfThumbnails.load(root.context, uri, width)?.let(show)
                }
            }
        }
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_CARD = 1
    }
}
