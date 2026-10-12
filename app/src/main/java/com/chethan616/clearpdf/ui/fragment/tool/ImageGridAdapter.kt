package com.chethan616.clearpdf.ui.fragment.tool

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import com.chethan616.clearpdf.R
import com.chethan616.clearpdf.databinding.ItemImageThumbBinding

/** Grid of picked images; the corner button removes one. */
class ImageGridAdapter(private val onRemove: (Int) -> Unit) : RecyclerView.Adapter<ImageGridAdapter.Holder>() {

    private var uris: List<Uri> = emptyList()

    fun submit(uris: List<Uri>) {
        if (uris == this.uris) return
        this.uris = uris
        notifyDataSetChanged()
    }

    override fun getItemCount() = uris.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemImageThumbBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(position)

    inner class Holder(private val b: ItemImageThumbBinding) : RecyclerView.ViewHolder(b.root) {
        init {
            b.remove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onRemove(pos)
            }
        }

        fun bind(index: Int) {
            b.image.load(uris[index])
            b.image.contentDescription = b.root.context.getString(R.string.image_number, index + 1)
        }
    }
}
