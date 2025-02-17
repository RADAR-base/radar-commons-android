package org.radarbase.monitor.application.ui.viewmodel.factory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.radarbase.monitor.application.ui.viewmodel.ApplicationMetricsViewModel
import org.radarbase.monitor.application.utils.AppRepository

class ApplicationMetricsViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ApplicationMetricsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ApplicationMetricsViewModel(
                repository.sourceStatusDao,
                repository.networkStatusDao
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
