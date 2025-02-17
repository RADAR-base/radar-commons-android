package org.radarbase.monitor.application.ui.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.radarbase.monitor.application.databinding.ClickableStringRowBinding

class StringAdapter(
    private val context: Context,
    private val contents: List<String>,
    private val itemClickAction: (String) -> Unit
) : RecyclerView.Adapter<StringAdapter.StringViewHolder>() {

    inner class StringViewHolder(val binding: ClickableStringRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindView(item: String) {
            binding.tvStringInfo.text = item
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StringViewHolder {
        val viewBinding = ClickableStringRowBinding.inflate(
            LayoutInflater.from(context),
            parent,
            false
        )

        return StringViewHolder(viewBinding)
    }

    override fun onBindViewHolder(holder: StringViewHolder, position: Int) {
        val content = contents[position]
        holder.bindView(content)
        holder.binding.clStringItem.setOnClickListener {
            content.apply(itemClickAction)
        }
    }

    override fun getItemCount(): Int = contents.size
}