package org.radarbase.android.storage.extract

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.radarbase.android.storage.dao.NetworkStatusDao
import org.radarbase.android.storage.dao.SourceStatusDao
import org.radarbase.android.storage.db.RadarApplicationDatabase
import org.radarbase.android.storage.entity.NetworkStatusLog
import org.radarbase.android.storage.entity.SourceStatusLog
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

@Suppress("unused")
class DatabaseToCSVExtractor {
    private var database: RadarApplicationDatabase? = null
    private var sourceStatusDao: SourceStatusDao? = null
    private var networkStatusDao: NetworkStatusDao? = null
    private var exportsDir: File? = null

    fun initialize(context: Context) {
        database = RadarApplicationDatabase.getInstance(context).also {
            this.sourceStatusDao = it.sourceStatusDao()
            this.networkStatusDao = it.networkStatusDao()
        }
        prepareExportDirectory(context)
    }

    @Throws(IllegalStateException::class)
    suspend fun exportEntities() {
        val sDao = sourceStatusDao
        val nDao = networkStatusDao
        check(sDao != null && nDao != null) { "Data Access Objects are not initialized yet." }

        clearExportDirectoryFiles()
        supervisorScope {
            launch {
                exportSourceEntitiesToCsvFile(
                    withContext(Dispatchers.IO) {
                        sDao.loadAllStatuses()
                    }
                )
            }
            launch {
                exportNetworkEntitiesToCSVFile(
                    withContext(Dispatchers.IO) {
                        nDao.loadAllNetworkLogs()
                    }
                )
            }
        }
    }

    /**
     * Exports the given list of SourceStatusLog entities directly to a CSV file using
     * a buffered stream, which minimizes memory overhead.
     *
     * @param sourceEntities List of [SourceStatusLog] objects to export.
     */
    private fun exportSourceEntitiesToCsvFile(sourceEntities: List<SourceStatusLog>) {
        val sourceStatusFile = SOURCE_STATUS_FILE
        val file = File(exportsDir, sourceStatusFile)

        file.bufferedWriter().use { writer ->
            writer.write("id,time,plugin,source_status")
            writer.newLine()

            sourceEntities.forEach { entity ->
                writer.write(
                    buildString {
                        append(entity.id)
                        append(',')
                        append(entity.time)
                        append(',')
                        append(entity.plugin)
                        append(',')
                        append(entity.sourceStatus)
                    }
                )
                writer.newLine()
            }
        }
        logger.info("Source CSV file exported to: ${file.absolutePath}")
    }

    /**
     * Exports the given list of NetworkStatusLog entities directly to a CSV file using
     * a buffered stream, which minimizes memory overhead.
     *
     * @param networkEntities List of [NetworkStatusLog] objects to export.
     */
    private fun exportNetworkEntitiesToCSVFile(networkEntities: List<NetworkStatusLog>) {
        val networkStatusFile = NETWORK_STATUS_FILE
        val file = File(exportsDir, networkStatusFile)

        file.bufferedWriter().use { writer ->
            writer.write("id,time,connectionStatus")
            writer.newLine()

            networkEntities.forEach { entity ->
                writer.write(
                    buildString {
                        append(entity.id)
                        append(',')
                        append(entity.time)
                        append(',')
                        append(entity.connectionStatus)
                    }
                )
                writer.newLine()
            }
        }
        logger.info("Network CSV file exported to: ${file.absolutePath}")
    }

    /**
     * Prepares the export directory within the internal cache directory.
     */
    private fun prepareExportDirectory(context: Context) {
        val internalDir: File = context.filesDir
        exportsDir = File(internalDir, APPLICATION_PLUGIN_EXPORT_PATH).also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    private fun clearExportDirectoryFiles() {
        exportsDir?.let { exports ->
            exports.listFiles { file ->
                file.name.startsWith(FILE_NAME_PREFIX) && file.name.endsWith(FILE_EXTENSION)
            }?.forEach { file ->
                if (file.delete()) {
                    logger.info("Deleted file: ${file.name}")
                } else {
                    logger.warn("Failed to delete file: ${file.name}")
                }
            }
        }
    }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(DatabaseToCSVExtractor::class.java)

        private const val FILE_NAME_PREFIX = "application_status_"
        private const val FILE_EXTENSION = ".csv"
        private const val SOURCE_STATUS_FILE = "${FILE_NAME_PREFIX}source${FILE_EXTENSION}"
        private const val NETWORK_STATUS_FILE = "${FILE_NAME_PREFIX}network${FILE_EXTENSION}"
        private const val APPLICATION_PLUGIN_EXPORT_PATH = "org.radarbase.monitor.application.exports"
    }
}
