package org.dashdeck.app.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.dashdeck.app.R
import org.dashdeck.app.bookmarks.BookmarkManager
import org.dashdeck.app.databinding.ItemStartPageSlotBinding

sealed interface QuickLinkItem {
    data class Link(val url: String, val title: String) : QuickLinkItem
    data object Add : QuickLinkItem
}

/** Quick link tiles on the start page, with an Add tile and an edit mode. */
class StartPageAdapter(
    private val bookmarkManager: BookmarkManager,
    private val resolveThemeColor: (Int) -> Int,
    private val onOpen: (String) -> Unit,
    private val onEdit: (QuickLinkItem.Link) -> Unit,
    private val onRemove: (QuickLinkItem.Link) -> Unit,
    private val onAdd: () -> Unit,
    private val onReordered: (List<String>) -> Unit
) : ListAdapter<QuickLinkItem, StartPageAdapter.TileViewHolder>(Diff) {

    var editing = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileViewHolder =
        TileViewHolder(ItemStartPageSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: TileViewHolder, position: Int) = holder.bind(getItem(position))

    fun canMove(position: Int) = getItem(position) is QuickLinkItem.Link

    fun onItemMove(from: Int, to: Int) {
        if (!canMove(from) || !canMove(to)) return
        val list = currentList.toMutableList()
        list.add(to, list.removeAt(from))
        submitList(list)
    }

    fun onMoveFinished() {
        onReordered(currentList.filterIsInstance<QuickLinkItem.Link>().map { it.url })
    }

    inner class TileViewHolder(private val binding: ItemStartPageSlotBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: QuickLinkItem) {
            val context = itemView.context
            binding.slotContentLayout.setBackgroundResource(R.drawable.tile_background)
            binding.iconContainer.removeAllViews()
            when (item) {
                is QuickLinkItem.Link -> {
                    binding.iconContainer.addView(
                        bookmarkManager.createSiteIconBadge(
                            url = item.url,
                            sizeDp = 36f,
                            cornerRadiusDp = 8f,
                            paddingDp = 6f,
                            backgroundColor = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest)
                        )
                    )
                    binding.titleText.text = item.title
                    binding.editBadge.isVisible = editing
                    binding.editBadge.setOnClickListener { onRemove(item) }
                    binding.slotContentLayout.setOnClickListener { if (editing) onEdit(item) else onOpen(item.url) }
                    binding.slotContentLayout.setOnLongClickListener { onEdit(item); true }
                    binding.slotContentLayout.contentDescription = item.title
                }
                QuickLinkItem.Add -> {
                    binding.iconContainer.addView(android.widget.ImageView(context).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
                        setImageResource(R.drawable.ic_add)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
                        )
                        scaleType = android.widget.ImageView.ScaleType.CENTER
                    })
                    binding.titleText.setText(R.string.quick_link_add)
                    binding.editBadge.isVisible = false
                    binding.slotContentLayout.setOnClickListener { onAdd() }
                    binding.slotContentLayout.setOnLongClickListener(null)
                    binding.slotContentLayout.contentDescription = context.getString(R.string.quick_link_add_title)
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<QuickLinkItem>() {
        override fun areItemsTheSame(oldItem: QuickLinkItem, newItem: QuickLinkItem) = when {
            oldItem is QuickLinkItem.Link && newItem is QuickLinkItem.Link -> oldItem.url == newItem.url
            else -> oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: QuickLinkItem, newItem: QuickLinkItem) = oldItem == newItem
    }
}
