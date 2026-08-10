package com.flipos.launcher.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * Replaces [current] with [new] using [DiffUtil] and dispatches minimal item
 * updates, instead of [RecyclerView.Adapter.notifyDataSetChanged] which rebinds
 * everything and drops D-pad focus. [sameItem] decides identity (usually a stable
 * key); content equality falls back to the item's own `equals` (data classes).
 */
fun <T> RecyclerView.Adapter<*>.submitWithDiff(
    current: MutableList<T>,
    new: List<T>,
    sameItem: (T, T) -> Boolean,
) {
    val old = ArrayList(current)
    val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            sameItem(old[oldItemPosition], new[newItemPosition])
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    })
    current.clear()
    current.addAll(new)
    diff.dispatchUpdatesTo(this)
}
