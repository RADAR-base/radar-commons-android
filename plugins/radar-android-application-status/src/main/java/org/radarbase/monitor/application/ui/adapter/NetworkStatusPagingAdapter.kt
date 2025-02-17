package org.radarbase.monitor.application.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.radarbase.android.storage.entity.NetworkStatusLog
import org.radarbase.monitor.application.R
import org.radarbase.monitor.application.databinding.ItemLogRowBinding
import org.radarbase.monitor.application.utils.dateTimeFromInstant

class NetworkStatusPagingAdapter(
    private val context: Context,
    private val onClickAction: (NetworkStatusLog) -> Unit
) : PagingDataAdapter<NetworkStatusLog, NetworkStatusPagingAdapter.NetworkStatusViewHolder>(
    DIFF_CALLBACK
) {

    inner class NetworkStatusViewHolder(private val binding: ItemLogRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(log: NetworkStatusLog) {
            binding.tvStatusInfo.text = context.getString(
                R.string.network_status_info,
                log.connectionStatus.name,
                dateTimeFromInstant(log.time)
            )

            binding.root.setOnClickListener {
                log.apply(onClickAction)
            }
        }
    }

    override fun onBindViewHolder(holder: NetworkStatusViewHolder, position: Int) {
        val log = getItem(position) ?: return
        holder.bind(log)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkStatusViewHolder {
        val binding = ItemLogRowBinding.inflate(LayoutInflater.from(context), parent, false)
        return NetworkStatusViewHolder(binding)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NetworkStatusLog>() {
            override fun areItemsTheSame(
                oldItem: NetworkStatusLog,
                newItem: NetworkStatusLog
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: NetworkStatusLog,
                newItem: NetworkStatusLog
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}