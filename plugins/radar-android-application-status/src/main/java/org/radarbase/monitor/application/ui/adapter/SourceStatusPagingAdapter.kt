package org.radarbase.monitor.application.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.radarbase.android.storage.entity.SourceStatusLog
import org.radarbase.monitor.application.R
import org.radarbase.monitor.application.databinding.ClickableStringRowBinding
import org.radarbase.monitor.application.utils.dateTimeFromInstant

class SourceStatusPagingAdapter(
    private val context: Context,
    private val onClickAction: (SourceStatusLog) -> Unit
) : PagingDataAdapter<SourceStatusLog, SourceStatusPagingAdapter.SourceStatusViewHolder>(
    DIFF_CALLBACK
) {

    inner class SourceStatusViewHolder(private val binding: ClickableStringRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(log: SourceStatusLog) {
            binding.tvStringInfo.text = context.getString(
                R.string.source_status_info,
                log.sourceStatus.name,
                dateTimeFromInstant(log.time)
            )

            binding.root.setOnClickListener {
                log.apply(onClickAction)
            }
        }
    }

    override fun onBindViewHolder(holder: SourceStatusViewHolder, position: Int) {
        val log = getItem(position) ?: return
        holder.bind(log)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceStatusViewHolder {
        val binding = ClickableStringRowBinding.inflate(LayoutInflater.from(context), parent, false)
        return SourceStatusViewHolder(binding)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SourceStatusLog>() {
            override fun areItemsTheSame(
                oldItem: SourceStatusLog,
                newItem: SourceStatusLog
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: SourceStatusLog,
                newItem: SourceStatusLog
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}