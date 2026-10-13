package com.chethan616.clearpdf.ui.fragment.tool

import android.net.Uri
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ItemImageThumbBinding
import com.chethan616.clearpdf.presentation.base.BaseListAdapter
import com.chethan616.clearpdf.presentation.base.BaseViewHolder
import com.chethan616.clearpdf.presentation.base.simpleDiff

/** Grid of picked images; the corner button removes one. */
class ImageGridAdapter(private val onRemove: (Int) -> Unit) :
    BaseListAdapter<ImageGridAdapter.Item, ItemImageThumbBinding>(ItemImageThumbBinding::inflate, simpleDiff { it.uri }) {

    data class Item(val index: Int, val uri: Uri)

    fun submit(uris: List<Uri>) = submitList(uris.mapIndexed(::Item))

    override fun onViewHolderCreated(holder: BaseViewHolder<ItemImageThumbBinding>) {
        holder.binding.remove.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onRemove(pos)
        }
    }

    override fun bind(binding: ItemImageThumbBinding, item: Item, position: Int) {
        binding.image.load(item.uri)
        binding.image.contentDescription = binding.root.context.getString(R.string.image_number, item.index + 1)
    }
}
