package org.radarbase.monitor.application.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.radarbase.monitor.application.databinding.ItemLogRowBinding

class StringAdapter(
    private val context: Context,
    private val contents: List<String>,
    private val itemClickAction: (String) -> Unit
) : RecyclerView.Adapter<StringAdapter.StringViewHolder>() {

    inner class StringViewHolder(val binding: ItemLogRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindView(item: String) {
            binding.tvStatusInfo.text = item
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StringViewHolder {
        val viewBinding = ItemLogRowBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )

        return StringViewHolder(viewBinding)
    }

    override fun onBindViewHolder(holder: StringViewHolder, position: Int) {
        val content = contents[position]
        holder.bindView(content)
        holder.binding.btnMoreInfo.setOnClickListener {
            content.apply(itemClickAction)
        }
    }

    override fun getItemCount(): Int = contents.size
}