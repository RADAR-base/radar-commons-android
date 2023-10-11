package nl.thehyve.prmt.shimmer

import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceManager
import org.radarbase.android.source.SourceService
import java.util.*

abstract class ShimmerService : SourceService<ShimmerSourceState>() {
    override val defaultState: ShimmerSourceState
        get() = ShimmerSourceState()

    override fun configureSourceManager(
        manager: SourceManager<ShimmerSourceState>,
        config: SingleRadarConfiguration,
    ) {
        manager as ShimmerManager
        manager.enabledSensors = config.getString("shimmer_sensors", "accelerometer gyroscope battery magnetometer barometer")
            .split(sensorSplitRegex)
            .mapNotNullTo(EnumSet.noneOf(ShimmerSensor::class.java)) { name ->
                val prefix = name.substring(0 until 3)
                ShimmerSensor.values().find { it.name.startsWith(prefix, ignoreCase = true) }
            }
        config.optDouble("shimmer_sampling_rate") {
            manager.samplingRate = it
        }
    }

    override fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) {
        println("***&& logout")
        super.logoutSucceeded(manager, authState)
        ShimmerSourceState.activeDevices.clear()
        println("***&& ShimmerSourceState.activeDevices" + ShimmerSourceState.activeDevices)
    }

    override fun onDestroy() {
        println("***&& onDestroy")
        super.onDestroy()
        ShimmerSourceState.activeDevices
            .filter { (_, idxCls) -> idxCls.second == this::class }
            .forEach { (address, idxCls) -> ShimmerSourceState.activeDevices.remove(address, idxCls) }
    }

    override fun createSourceManager(): ShimmerManager = ShimmerManager(this, this::class)

    companion object {
        private val sensorSplitRegex = "[\\s,]+".toRegex()
    }
}
