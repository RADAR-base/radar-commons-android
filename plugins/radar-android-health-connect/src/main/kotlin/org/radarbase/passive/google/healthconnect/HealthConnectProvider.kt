package org.radarbase.passive.google.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.runBlocking
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider
import org.radarbase.android.util.PermissionRequester
import org.radarbase.passive.google.healthconnect.HealthConnectService.Companion.HEALTH_CONNECT_DATA_TYPES

class HealthConnectProvider(radarService: RadarService) : SourceProvider<BaseSourceState>(radarService)
{
    override val pluginNames: List<String> = listOf("health_connect")
    override val serviceClass: Class<HealthConnectService> = HealthConnectService::class.java
    override val displayName: String
        get() = radarService.getString(R.string.google_health_connect_display)
    override val sourceProducer: String = "Google"
    override val sourceModel: String = "HealthConnect"
    override val version: String = "1.0.0"
    override val permissionsNeeded: List<String> = listOf()

    // Health Connect can function with not all permissions granted.
    override val permissionsRequested: List<String>
        get() {
            val configuredTypes = radarService.configuration.config.value
                ?.optString(HEALTH_CONNECT_DATA_TYPES)
                ?: return listOf()

            return configuredTypes
                .toHealthConnectTypes()
                .map { HealthPermission.getReadPermission(it) }
        }

    override val requestPermissionResultContract: List<PermissionRequester> = listOf(createPermissionResultContract())

    private fun createPermissionResultContract(): PermissionRequester = PermissionRequester(
        permissions = healthConnectNames.keys
            .mapTo(HashSet()) { HealthPermission.getReadPermission(it) },
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { permissions ->
        runBlocking {
            HealthConnectClient.getOrCreate(this@PermissionRequester)
                .permissionController
                .getGrantedPermissions()
                .intersect(permissions)
        }
    }
}
