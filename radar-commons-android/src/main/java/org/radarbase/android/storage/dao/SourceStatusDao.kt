package org.radarbase.android.storage.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import org.radarbase.android.storage.entity.SourceStatusLog

/**
 * Data Access Object (DAO) for performing operations on the [SourceStatusLog] table.
 *
 * This DAO extends [BaseDao] to provide basic CRUD operations and adds additional queries
 * specific to [SourceStatusLog] such as fetching records within a time range and deleting
 * records based on specific criteria.
 */
@Suppress("unused")
@Dao
abstract class SourceStatusDao : BaseDao<SourceStatusLog> {

    /**
     * Retrieves all the source status log records from the database.
     *
     * @return a list of all [SourceStatusLog] entries.
     */
    @Query("SELECT * FROM source_status_log")
    abstract suspend fun loadAllStatuses(): List<SourceStatusLog>

    /**
     * Retrieves source status log records that have a [SourceStatusLog.time] value between the given [from] and [to] timestamps.
     *
     * @param from the start timestamp (inclusive).
     * @param to the end timestamp (inclusive).
     * @return a list of [SourceStatusLog] entries that fall within the specified time range.
     */
    @Query("SELECT * FROM source_status_log WHERE time BETWEEN :from AND :to")
    abstract suspend fun loadStatusesBetweenTimestamps(from: Long, to: Long): List<SourceStatusLog>

    /**
     * Retrieves records with a [SourceStatusLog.time] value less than the specified [time].
     *
     * This can be used to fetch records that are older than the given timestamp,
     * for instance, to delete logs older than 7 days.
     *
     * @param time the timestamp threshold, logs with a time less than this value are considered old.
     * @return a list of [SourceStatusLog] entries with a [time] value less than the specified threshold.
     */
    @Query("SELECT * FROM source_status_log WHERE time < :time")
    abstract suspend fun loadStatusesOlderThan(time: Long): List<SourceStatusLog>

    /**
     * Retrieves a list of distinct plugin.
     *
     * @return a list of unique plugin names.
     */
    @Query("SELECT DISTINCT plugin FROM source_status_log")
    abstract suspend fun loadDistinctPlugins(): List<String>

    /**
     * Retrieves all [SourceStatusLog] records that match the specified plugin name.
     *
     * This query filters the records in the source_status_log table based on the value of the
     * [SourceStatusLog.plugin].
     *
     * @param pluginName the name of the plugin to filter by.
     * @return a list of [SourceStatusLog] entries corresponding to the specified plugin.
     */
    @Query("SELECT * FROM source_status_log WHERE plugin = :pluginName")
    abstract suspend fun loadStatusesByPluginName(pluginName: String): List<SourceStatusLog>

    /**
     * Returns a [PagingSource] for loading [SourceStatusLog] records in pages.
     *
     * The [PagingSource] loads data in chunks (pages) as needed when the user scrolls,
     * rather than loading all records at once. The records are sorted by [SourceStatusLog.time] in descending order.
     *
     * @return a [PagingSource] that loads pages of [SourceStatusLog] items.
     */
    @Query("SELECT * FROM source_status_log WHERE plugin = :pluginName ORDER BY time ASC")
    abstract fun pagingSourceByPluginName(pluginName: String): PagingSource<Long, SourceStatusLog>

    /**
     * Retrieves the total number of source status logs.
     *
     * @return the count of source status.
     */
    @Query("SELECT COUNT(*) FROM source_status_log")
    abstract suspend fun getStatusesCount(): Int

    /**
     * Deletes source status log records that have a [SourceStatusLog.time] value between the specified [from] and [to] timestamps.
     *
     * @param from the start timestamp (inclusive) for deletion.
     * @param to the end timestamp (inclusive) for deletion.
     */
    @Query("DELETE FROM source_status_log WHERE time BETWEEN :from AND :to")
    abstract suspend fun deleteStatusesBetweenTimestamps(from: Long, to: Long)

    /**
     * Deletes the source status log record with the specified [id].
     *
     * @param id the unique identifier of the status log to delete.
     */
    @Query("DELETE FROM source_status_log WHERE id = :id")
    abstract suspend fun deleteStatusById(id: Long)

    /**
     * Deletes the oldest source status log records to ensure that the total number of records does not exceed [count].
     *
     * Calculates the number of records that exceed the retention count and then deletes that many
     * oldest records based on the [SourceStatusLog.time].
     *
     * For example: if there are 100 records and [count] is set to 80, then the 20 oldest records (based on [SourceStatusLog.time])
     * will be deleted.
     *
     * @param count the maximum number of recent records to retain.
     * @return the number of rows that were deleted.
     *
     */
    @Query(
        """
    DELETE FROM source_status_log 
    WHERE id IN (
        SELECT id FROM source_status_log 
        ORDER BY time ASC 
        LIMIT (SELECT COUNT(*) - :count FROM source_status_log)
    )
"""
    )
    abstract suspend fun deleteStatusesCountGreaterThan(count: Long): Int

    /**
     * Deletes source status logs that are older than the specified [time] threshold.
     *
     * Useful for removing records that are older than a certain retention period.
     *
     * @param time the timestamp threshold. All network status logs with a `time` less than this value will be deleted.
     * @return the number of rows that were deleted.
     */
    @Query(
        """
    DELETE FROM source_status_log WHERE time < :time
    """
    )
    abstract suspend fun deleteSourceLogsOlderThan(time: Long): Int

}