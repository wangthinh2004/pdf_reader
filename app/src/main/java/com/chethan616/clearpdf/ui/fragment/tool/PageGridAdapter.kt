package com.chethan616.clearpdf.ui.fragment.tool

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ItemPageThumbBinding
import com.chethan616.clearpdf.ui.common.color
import com.chethan616.clearpdf.ui.common.dp
import com.chethan616.clearpdf.ui.common.tintBackground
import com.chethan616.clearpdf.ui.common.withAlpha

/** Grid of page thumbnails the user taps to select pages. */
class PageGridAdapter(
    @ColorInt private val accent: Int,
    private val onToggle: (Int) -> Unit
) : RecyclerView.Adapter<PageGridAdapter.Holder>() {

    private var pageCount = 0
    private var thumbs: List<Bitmap?> = emptyList()
    private var selected: Set<Int> = emptySet()

    fun submit(pageCount: Int, thumbs: List<Bitmap?>, selected: Collection<Int>) {
        val countChanged = pageCount != this.pageCount
        val thumbsChanged = thumbs !== this.thumbs
        val newSelected = selected.toSet()
        val oldSelected = this.selected
        this.pageCount = pageCount
        this.thumbs = thumbs
        this.selected = newSelected
        when {
            countChanged || thumbsChanged -> notifyDataSetChanged()
            else -> (oldSelected union newSelected).minus(oldSelected intersect newSelected).forEach { notifyItemChanged(it) }
        }
    }

    override fun getItemCount() = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPageThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    inner class Holder(private val b: ItemPageThumbBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onToggle(pos)
            }
        }

        fun bind(index: Int) {
            val ctx = b.root.context
            val isSelected = index in selected
            val thumb = thumbs.getOrNull(index)
            if (thumb != null) b.image.setImageBitmap(thumb) else b.image.setImageDrawable(null)
            b.label.text = "${index + 1}"
            b.label.setBackgroundColor(if (isSelected) withAlpha(accent, 0.85f) else withAlpha(0xFF000000.toInt(), 0.45f))
            b.card.strokeColor = if (isSelected) accent else ctx.color(R.color.app_divider)
            b.card.strokeWidth = ctx.dp(if (isSelected) 3 else 1)
            b.check.isVisible = isSelected
            b.check.tintBackground(accent)
            b.root.contentDescription = ctx.getString(R.string.page_number, index + 1)
        }
    }

    companion object {
        /** A grid whose column count fits ~76 dp cells into [recyclerView]'s width. */
        fun attach(recyclerView: RecyclerView, adapter: RecyclerView.Adapter<*>) {
            val lm = GridLayoutManager(recyclerView.context, 4)
            recyclerView.layoutManager = lm
            recyclerView.adapter = adapter
            recyclerView.isNestedScrollingEnabled = false
            recyclerView.itemAnimator = null
            recyclerView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    lm.spanCount = ((right - left) / v.context.dp(76)).coerceAtLeast(3)
                }
            }
        }
    }
}
