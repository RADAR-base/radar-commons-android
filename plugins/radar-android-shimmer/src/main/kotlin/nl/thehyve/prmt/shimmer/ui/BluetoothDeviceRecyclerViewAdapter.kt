package nl.thehyve.prmt.shimmer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import nl.thehyve.prmt.shimmer.databinding.ShimmerNewDeviceItemBinding

class BluetoothDeviceRecyclerViewAdapter(
    private val context: Context,
    private val callback: (BluetoothDeviceState) -> Unit,
) : RecyclerView.Adapter<BluetoothDeviceViewHolder>() {
    init {
        setHasStableIds(true)
    }

    var mNewDevices: List<BluetoothDeviceState> = listOf()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val onClickListener: (BluetoothDeviceState) -> View.OnClickListener = { item ->
        View.OnClickListener { callback(item) }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): BluetoothDeviceViewHolder {
        val itemView = ShimmerNewDeviceItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BluetoothDeviceViewHolder(itemView, onClickListener)
    }

    override fun getItemId(position: Int): Long = mNewDevices[position].id

    override fun getItemCount(): Int = mNewDevices.size

    override fun onBindViewHolder(holder: BluetoothDeviceViewHolder, position: Int) {
        holder.updateValue(mNewDevices[position], context)
    }
}
