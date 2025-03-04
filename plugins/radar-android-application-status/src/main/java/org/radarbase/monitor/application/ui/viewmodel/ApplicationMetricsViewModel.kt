package org.radarbase.monitor.application.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import org.radarbase.android.storage.dao.NetworkStatusDao
import org.radarbase.android.storage.dao.SourceStatusDao

class ApplicationMetricsViewModel(
    private val sourceStatusDao: SourceStatusDao,
    private val networkStatusDao: NetworkStatusDao
) : ViewModel() {
    fun loadSourceStatusCount(): LiveData<Int> {
        return sourceStatusDao.getStatusesCount()
    }

    fun loadNetworkStatusCount(): LiveData<Int> {
        return networkStatusDao.getStatusesCount()
    }
}