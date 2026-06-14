package org.radarbase.passive.polar

import org.radarbase.android.source.BaseSourceState

class PolarState : BaseSourceState() {
    override val acceleration = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    @set:Synchronized
    override var batteryLevel = Float.NaN

    override val hasAcceleration: Boolean = true

    @Volatile
    var deviceName: String? = null

    /**
     * Whether data is actively streaming.
     */
    @Volatile
    var isCollecting: Boolean = false

    /**
     * Whether the user has asked to connect (tapped Connect).
     */
    @Volatile
    var connectionRequested: Boolean = false

    @Volatile
    var polarController: PolarController? = null

    @Synchronized
    fun setAcceleration(x: Float, y: Float, z: Float) {
        this.acceleration[0] = x
        this.acceleration[1] = y
        this.acceleration[2] = z
    }

    interface PolarController {
        fun connectDevice()
        fun startCollecting()
        fun stopCollecting()
    }
}
