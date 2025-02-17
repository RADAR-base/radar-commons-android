package org.radarbase.android.storage.converter

import androidx.room.TypeConverter
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.storage.entity.NetworkStatus

@Suppress("unused")
class Converters {
    @TypeConverter
    fun fromSourceStatus(status: SourceStatusListener.Status): String = status.name

    @TypeConverter
    fun toSourceStatus(statusName: String): SourceStatusListener.Status =
        SourceStatusListener.Status.valueOf(statusName)

    @TypeConverter
    fun fromNetworkStatus(status: NetworkStatus): String = status.name

    @TypeConverter
    fun toNetworkStatus(statusName: String): NetworkStatus =
        NetworkStatus.valueOf(statusName)
}
