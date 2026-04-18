package com.example.ar_control.ui.clips

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ar_control.databinding.ItemRecordedClipBinding

class RecordedClipAdapter(
    private val onClipClicked: (String) -> Unit
) : ListAdapter<RecordedClipListItem, RecordedClipAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordedClipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRecordedClipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.isCheckable = true
        }

        fun bind(item: RecordedClipListItem) {
            binding.root.isChecked = item.isSelected
            binding.clipTitleText.text = item.title
            binding.clipSubtitleText.text = item.subtitle
            binding.root.setOnClickListener { onClipClicked(item.id) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<RecordedClipListItem>() {
        override fun areItemsTheSame(
            oldItem: RecordedClipListItem,
            newItem: RecordedClipListItem
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: RecordedClipListItem,
            newItem: RecordedClipListItem
        ): Boolean = oldItem == newItem
    }
}
