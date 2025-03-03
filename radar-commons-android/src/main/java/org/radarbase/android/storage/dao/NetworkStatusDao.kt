package org.radarbase.android.storage.dao

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import org.radarbase.android.storage.entity.NetworkStatusLog
import org.radarbase.android.storage.entity.SourceStatusLog

/**
 * Data Access Object (DAO) for performing operations on the [NetworkStatusLog] table.
 *
 * This DAO extends [BaseDao] to provide standard CRUD operations and adds methods
 * specific to managing network status logs, such as retrieving logs within a time range,
 * fetching logs older than a given timestamp, and deleting logs based on time or count constraints.
 */
@Suppress("unused")
@Dao
abstract class NetworkStatusDao : BaseDao<NetworkStatusLog> {

    /**
     * Retrieves all network status log records from the database.
     *
     * @return a list of all [NetworkStatusLog] entries.
     */
    @Query("SELECT * FROM network_status_log")
    abstract suspend fun loadAllNetworkLogs(): List<NetworkStatusLog>

    /**
     * Retrieves network status log records with a [NetworkStatusLog.time] value between the specified [from] and [to] timestamps.
     *
     * @param from the start timestamp (inclusive).
     * @param to the end timestamp (inclusive).
     * @return a list of [NetworkStatusLog] entries within the specified time range.
     */
    @Query("SELECT * FROM network_status_log WHERE time BETWEEN :from AND :to")
    abstract suspend fun loadNetworkLogsBetweenTimestamps(
        from: Long,
        to: Long
    ): List<NetworkStatusLog>

    /**
     * Retrieves network status log records with a [NetworkStatusLog.time] value less than the specified [time].
     *
     * This method is useful for fetching logs that are older than the given timestamp,
     * for example, when you want to delete logs older than 7 days.
     *
     * @param time the timestamp threshold, logs with a time less than this value are considered old.
     * @return a list of [NetworkStatusLog] entries with a [time] value less than the specified threshold.
     */
    @Query("SELECT * FROM network_status_log WHERE time < :time")
    abstract suspend fun loadNetworkLogsOlderThan(time: Long): List<NetworkStatusLog>

    /**
     * Returns a [PagingSource] for loading [NetworkStatusLog] records in pages.
     *
     * The [PagingSource] loads data in chunks (pages) as needed when the user scrolls,
     * rather than loading all records at once. The records are sorted by [NetworkStatusLog.time] in descending order.
     *
     * @return a [PagingSource] that loads pages of [SourceStatusLog] items.
     */
    @Query("SELECT * FROM network_status_log ORDER BY time ASC")
    abstract fun pagingSource(): PagingSource<Int, NetworkStatusLog>

    /**
     * Retrieves the total number of status logs present in the table.
     *
     * @return the count of status logs.
     */
    @Query("SELECT COUNT(*) FROM network_status_log")
    abstract fun getStatusesCount(): LiveData<Int>

    /**
     * Deletes network status log records with a [NetworkStatusLog.time] value between the specified [from] and [to] timestamps.
     *
     * @param from the start timestamp (inclusive) for deletion.
     * @param to the end timestamp (inclusive) for deletion.
     */
    @Query("DELETE FROM network_status_log WHERE time BETWEEN :from AND :to")
    abstract suspend fun deleteNetworkLogsBetweenTimestamps(from: Long, to: Long)

    /**
     * Deletes the network status log record with the specified [id].
     *
     * @param id the unique identifier of the network log to delete.
     */
    @Query("DELETE FROM network_status_log WHERE id = :id")
    abstract suspend fun deleteNetworkLogById(id: Long)

    /**
     * Deletes the oldest network status log records so that the total number of records does not exceed [count].
     *
     * Calculates how many records are in excess by subtracting [count] from the total number
     * of records in the table. It then deletes that many oldest records.
     *
     * For example, if the table has 100 records and [count] is set to 80, then the 20 oldest records will be deleted.
     *
     * @param count the maximum number of recent records to retain.
     * @return the number of rows that were deleted.
     *
     */
    @Query(
        """
        DELETE FROM network_status_log 
        WHERE id IN (
            SELECT id FROM network_status_log 
            ORDER BY time ASC 
            LIMIT (SELECT COUNT(*) - :count FROM network_status_log)
        )
        """
    )
    abstract suspend fun deleteNetworkLogsCountGreaterThan(count: Long): Int

    /**
     * Deletes network status log records that are older than the specified [time] threshold.
     *
     * Useful for removing logs that are older than a certain retention period.
     *
     * @param time the timestamp threshold. All network status logs with a `time` less than this value will be deleted.
     * @return the number of rows that were deleted.
     */
    @Query(
        """
    DELETE FROM network_status_log WHERE time <= :time
    """
    )
    abstract suspend fun deleteNetworkLogsOlderThan(time: Long): Int
}