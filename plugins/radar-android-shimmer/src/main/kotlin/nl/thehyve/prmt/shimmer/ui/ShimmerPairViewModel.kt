package nl.thehyve.prmt.shimmer.ui

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.BOND_BONDED
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import nl.thehyve.prmt.shimmer.ShimmerSourceState
import nl.thehyve.prmt.shimmer.ui.BluetoothDeviceState.Companion.bluetoothDevice
import okhttp3.internal.toImmutableMap
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong

data class BluetoothDeviceState(
    val id: Long,
    val address: String,
    val name: String?,
    val device: BluetoothDevice,
    val state: BondState,
) {
    companion object {
        private val deviceMap: ConcurrentMap<String, Long> = ConcurrentHashMap()
        private val nextId = AtomicLong(1L)

        fun bluetoothDevice(device: BluetoothDevice, name: String?, state: BondState): BluetoothDeviceState {
            val address = device.address
            val id = deviceMap[address]
                ?: nextId.getAndIncrement()
            return BluetoothDeviceState(id, address, name, device, state)
        }
    }

    enum class BondState {
        UNKNOWN, FOUND, PAIRED, CONFIGURED
    }
}

class ShimmerPairViewModel : ViewModel() {
    val pairedDevices = MutableStateFlow<Map<String, BluetoothDeviceState?>>(emptyMap())
    val potentialDevices = MutableStateFlow<Map<String, BluetoothDeviceState>>(emptyMap())

    fun addPotentialDevice(device: BluetoothDevice) {
        val paired = pairedDevices.value
        val existingDevicePair = paired.entries.find { (_, value) -> value?.address == device.address }
        if (device.address in ShimmerSourceState.activeDevices) {

            logger.info("Skipping already configured Bluetooth device {}", device.address)
            return
        }
        try {
            val state = when {
                device.address in ShimmerSourceState.activeDevices -> BluetoothDeviceState.BondState.CONFIGURED
                device.bondState == BOND_BONDED -> BluetoothDeviceState.BondState.PAIRED
                else -> BluetoothDeviceState.BondState.FOUND
            }
            if (existingDevicePair != null) {
                val existingDevice = checkNotNull(existingDevicePair.value)
                pairedDevices.value = buildTreeMap(paired) {
                    put(
                        existingDevicePair.key,
                        existingDevice.copy(
                            name = device.name,
                            device = device,
                            state = state,
                        )
                    )
                }
            } else {
                potentialDevices.value = buildMap {
                    putAll(potentialDevices.value)
                    put(
                        device.address,
                        bluetoothDevice(
                            device = device,
                            name = device.name,
                            state = state,
                        ),
                    )
                }
            }
        } catch (ex: SecurityException) {
            // do nothing
        }
    }
    fun setPairedDevice(sourceModel: String, state: BluetoothDeviceState) {
        pairedDevices.value = buildTreeMap(pairedDevices.value) {
            put(sourceModel, state)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ShimmerPairViewModel::class.java)

        inline fun <K, V> buildTreeMap(
            map: Map<K, V>,
            builder: TreeMap<K, V>.() -> Unit,
        ): Map<K, V> = Collections.unmodifiableMap(
            TreeMap(map).apply(builder)
        )
    }
}
