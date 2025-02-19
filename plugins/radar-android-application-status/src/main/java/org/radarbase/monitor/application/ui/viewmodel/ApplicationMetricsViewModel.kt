package org.radarbase.monitor.application.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import org.radarbase.android.storage.dao.NetworkStatusDao
import org.radarbase.android.storage.dao.SourceStatusDao
import org.radarbase.android.storage.entity.NetworkStatusLog
import org.radarbase.android.storage.entity.SourceStatusLog

@Suppress("unused")
class ApplicationMetricsViewModel(
    private val sourceStatusDao: SourceStatusDao,
    private val networkStatusDao: NetworkStatusDao
) : ViewModel() {

    val networkStatusPagingData: Flow<PagingData<NetworkStatusLog>> = Pager(
        config = PagingConfig(
            pageSize = 20,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            networkStatusDao.pagingSource()
        }
    ).flow.cachedIn(viewModelScope)

    fun getSourceStatusForPagingData(pluginName: String): Flow<PagingData<SourceStatusLog>> {
        return Pager (
            config = PagingConfig(
                pageSize = 20,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                sourceStatusDao.pagingSourceByPluginName(pluginName)
            }
        ).flow.cachedIn(viewModelScope)
    }

    suspend fun loadDistinctPluginsFromSourceLogs(): List<String> {
        return sourceStatusDao.loadDistinctPlugins()
    }

    suspend fun loadStatusByPluginNameFromSourceLogs(pluginName: String): List<SourceStatusLog> {
        return sourceStatusDao.loadStatusesByPluginName(pluginName)
    }

    suspend fun loadAllNetworkLogs(): List<NetworkStatusLog> {
        return networkStatusDao.loadAllNetworkLogs()
    }

    suspend fun loadSourceStatusCount(): Int {
        return sourceStatusDao.getStatusesCount()
    }

    suspend fun loadNetworkStatusCount(): Int {
        return networkStatusDao.getStatusesCount()
    }
}