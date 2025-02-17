package org.radarbase.monitor.application.utils

import org.radarbase.android.storage.dao.NetworkStatusDao
import org.radarbase.android.storage.dao.SourceStatusDao
import org.radarbase.android.storage.db.RadarApplicationDatabase

class AppRepository(private val db: RadarApplicationDatabase) {
    val sourceStatusDao: SourceStatusDao = db.sourceStatusDao()
    val networkStatusDao: NetworkStatusDao = db.networkStatusDao()
}
