package com.chethan616.clearpdf.presentation.base

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

class BaseViewHolder<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

/**
 * A [ListAdapter] over one ViewBinding item layout. Lists are diffed on a background thread, so
 * only changed rows rebind (no notifyDataSetChanged). Wire click listeners once in
 * [onViewHolderCreated], bind data in [bind].
 */
abstract class BaseListAdapter<T : Any, VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
    diff: DiffUtil.ItemCallback<T>
) : ListAdapter<T, BaseViewHolder<VB>>(diff) {

    final override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder<VB> =
        BaseViewHolder(inflate(LayoutInflater.from(parent.context), parent, false)).also(::onViewHolderCreated)

    final override fun onBindViewHolder(holder: BaseViewHolder<VB>, position: Int) =
        bind(holder.binding, getItem(position), position)

    protected open fun onViewHolderCreated(holder: BaseViewHolder<VB>) {}

    protected abstract fun bind(binding: VB, item: T, position: Int)
}

/** Diff callback that treats items with the same [key] as the same row and compares the rest by equals. */
fun <T : Any, K> simpleDiff(key: (T) -> K): DiffUtil.ItemCallback<T> = object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = key(oldItem) == key(newItem)

    @SuppressLint("DiffUtilEquals")
    override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
}
