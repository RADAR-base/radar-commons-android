package org.radarbase.android.storage.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.radarbase.android.source.SourceStatusListener

@Entity(
    tableName = "source_status_log",
    indices = [
        Index(value = ["time"]),
        Index(value = ["plugin"])
    ]
)
data class SourceStatusLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val time: Long,
    val plugin: String,
    @ColumnInfo("source_status") val sourceStatus: SourceStatusListener.Status
)