package nl.thehyve.prmt.shimmer.ui

import android.content.Context
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.recyclerview.widget.RecyclerView
import nl.thehyve.prmt.shimmer.R
import nl.thehyve.prmt.shimmer.databinding.ShimmerNewDeviceItemBinding

class BluetoothDeviceViewHolder(
    private val binding: ShimmerNewDeviceItemBinding,
    private val onClickListener: (BluetoothDeviceState) -> View.OnClickListener,
) : RecyclerView.ViewHolder(binding.root) {

    private var currentValue: BluetoothDeviceState? = null

    fun updateValue(value: BluetoothDeviceState, context: Context) {
        if (currentValue == value) {
            return
        }
        itemView.setOnClickListener(onClickListener(value))
        val previousValue = currentValue
        if (previousValue == null || previousValue.name != value.name) {
            binding.deviceName.text = value.name ?: context.getString(R.string.emptyText)
        }
        if (previousValue == null || previousValue.address != value.address) {
            binding.deviceAddress.text = value.address
        }
        if (previousValue == null || previousValue.state != value.state) {
            if (value.state == BluetoothDeviceState.BondState.UNKNOWN) {
                binding.deviceStatus.visibility = GONE
            } else {
                binding.deviceStatus.visibility = VISIBLE
                binding.deviceStatus.setImageResource(
                    when (value.state) {
                        BluetoothDeviceState.BondState.CONFIGURED -> R.drawable.twotone_lock_24
                        BluetoothDeviceState.BondState.PAIRED -> R.drawable.baseline_circle_green_700_24dp
                        else -> R.drawable.twotone_circle_24
                    }
                )
            }
        }
        currentValue = value
    }
}
