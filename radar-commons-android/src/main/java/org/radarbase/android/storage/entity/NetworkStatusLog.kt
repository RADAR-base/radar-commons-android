package org.radarbase.android.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "network_status_log",
    indices = [Index(value = ["time"])]
)
data class NetworkStatusLog(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val time: Long,
    @ColumnInfo("connection_state") val connectionState: NetworkStatus
)