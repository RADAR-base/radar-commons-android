package org.radarbase.android.source

import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.kafka.ServerStatusListener
import org.radarbase.data.RecordData
import java.io.IOException
import java.util.*

class SourceServiceBinder<T : BaseSourceState>(private val sourceService: SourceService<T>) : Binder(), SourceBinder<T> {
    @Throws(IOException::class)
    override fun getRecords(topic: String, limit: Int): RecordData<Any, Any>? {
        val localDataHandler = sourceService.dataHandler ?: return null
        return localDataHandler.getCache(topic).getRecords(limit)
    }

    override var manualAttributes: Map<String, String>
        get() = sourceService.manualAttributes
        set(value) {
            sourceService.manualAttributes = value
        }

    override val registeredSource: SourceMetadata?
        get() = sourceService.registeredSource

    override val sourceState: T
        get() = sourceService.state

    override val sourceName: String?
        get() = sourceService.sourceManager?.name

    override fun startRecording(acceptableIds: Set<String>) {
        sourceService.startRecording(acceptableIds)
    }

    override fun restartRecording(acceptableIds: Set<String>) {
        sourceService.restartRecording(acceptableIds)
    }

    override fun stopRecording() {
        sourceService.stopRecording()
    }

    override val serverStatus: ServerStatusListener.Status
        get() = sourceService.dataHandler?.status ?: ServerStatusListener.Status.DISCONNECTED

    override val serverRecordsSent: Map<String, Long>
        get() = sourceService.dataHandler?.recordsSent ?: mapOf()

    override fun updateConfiguration(bundle: Bundle) {
        sourceService.onInvocation(bundle)
    }

    override val numberOfRecords: Long?
        get() = sourceService.dataHandler?.let { data ->
            data.caches.sumOf { it.numberOfRecords }
        }

    override fun needsBluetooth(): Boolean = sourceService.isBluetoothConnectionRequired

    override fun shouldRemainInBackground(): Boolean = sourceService.state.status !in nonFunctioningStates

    public override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        throw UnsupportedOperationException()
    }

    companion object {
        private val nonFunctioningStates: Set<SourceStatusListener.Status> = EnumSet.of(
            SourceStatusListener.Status.DISCONNECTED,
            SourceStatusListener.Status.UNAVAILABLE,
        )
    }
}
