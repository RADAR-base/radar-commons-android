package org.radarbase.passive.empatica

import com.empatica.empalink.EmpaticaDevice
import com.empatica.empalink.config.EmpaSensorType
import com.empatica.empalink.config.EmpaSessionEvent
import com.empatica.empalink.config.EmpaStatus
import com.empatica.empalink.delegate.EmpaDataDelegate
import com.empatica.empalink.delegate.EmpaSessionManagerDelegate
import com.empatica.empalink.delegate.EmpaStatusDelegate

class E4Delegate(private val e4Service: E4Service) : EmpaDataDelegate, EmpaStatusDelegate, EmpaSessionManagerDelegate {
    private val deviceManager: E4Manager?
        get() = e4Service.sourceManager as E4Manager?

    override fun didReceiveGSR(gsr: Float, timestamp: Double): Unit = deviceManager?.didReceiveGSR(gsr, timestamp) ?: Unit
    override fun didReceiveBVP(bvp: Float, timestamp: Double) = deviceManager?.didReceiveBVP(bvp, timestamp) ?: Unit
    override fun didReceiveIBI(ibi: Float, timestamp: Double) = deviceManager?.didReceiveIBI(ibi, timestamp) ?: Unit
    override fun didReceiveTemperature(t: Float, timestamp: Double) = deviceManager?.didReceiveTemperature(t, timestamp) ?: Unit
    override fun didReceiveAcceleration(x: Int, y: Int, z: Int, timestamp: Double) = deviceManager?.didReceiveAcceleration(x, y, z, timestamp) ?: Unit
    override fun didReceiveBatteryLevel(level: Float, timestamp: Double) = deviceManager?.didReceiveBatteryLevel(level, timestamp) ?: Unit
    override fun didReceiveTag(timestamp: Double) = deviceManager?.didReceiveTag(timestamp) ?: Unit

    override fun didUpdateStatus(status: EmpaStatus) = deviceManager?.didUpdateStatus(status) ?: Unit
    override fun didEstablishConnection() = deviceManager?.didEstablishConnection() ?: Unit
    override fun didUpdateSensorStatus(status: Int, type: EmpaSensorType) = deviceManager?.didUpdateSensorStatus(status, type) ?: Unit
    override fun didDiscoverDevice(device: EmpaticaDevice, deviceLabel: String, rssi: Int, allowed: Boolean) = deviceManager?.didDiscoverDevice(device, deviceLabel, rssi, allowed) ?: Unit
    override fun didRequestEnableBluetooth() = deviceManager?.didRequestEnableBluetooth() ?: Unit
    override fun didUpdateOnWristStatus(status: Int) = deviceManager?.didUpdateOnWristStatus(status) ?: Unit

    override fun didUpdateSessionStatus(event: EmpaSessionEvent?, progress: Float) = deviceManager?.didUpdateSessionStatus(event, progress) ?: Unit
}
