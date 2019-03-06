package org.radarcns.android.device

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import org.apache.avro.specific.SpecificRecord
import org.radarcns.android.kafka.ServerStatusListener
import org.radarcns.data.RecordData
import java.io.IOException

class DeviceBinder<T : BaseDeviceState>(private val deviceService: DeviceService<T>) : Binder(), DeviceServiceBinder<T> {
    @Throws(IOException::class)
    override fun getRecords(topic: String, limit: Int): RecordData<Any, Any>? {
        val localDataHandler = deviceService.dataHandler ?: return null
        return localDataHandler.getCache(topic).getRecords(limit)
    }

    override val deviceStatus: T
        get() = deviceService.state

    override val deviceName: String?
        get() = deviceService.deviceManager?.name

    override fun startRecording(acceptableIds: Set<String>) {
        deviceService.startRecording(acceptableIds)
    }

    override fun stopRecording() {
        deviceService.stopRecording()
    }

    override val serverStatus: ServerStatusListener.Status
        get() = deviceService.dataHandler?.status ?: ServerStatusListener.Status.DISCONNECTED

    override val serverRecordsSent: Map<String, Int>
        get() = deviceService.dataHandler?.recordsSent ?: mapOf()

    override fun updateConfiguration(bundle: Bundle) {
        deviceService.onInvocation(bundle)
    }

    override val numberOfRecords: Long?
        get() = deviceService.dataHandler?.let { data ->
            data.caches
                    .map { it.numberOfRecords }
                    .reduce { acc, num -> acc + num }
        }

    override fun needsBluetooth(): Boolean {
        return deviceService.isBluetoothConnectionRequired
    }

    override fun shouldRemainInBackground(): Boolean {
        return deviceService.state.status != DeviceStatusListener.Status.DISCONNECTED
    }

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        throw UnsupportedOperationException()
    }
}
