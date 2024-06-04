package org.radarbase.passive.polarh10

import org.radarbase.android.source.BaseSourceState

/**
 * The status on a single point in time
 */
class PolarH10State : BaseSourceState() {
    override val acceleration = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    @set:Synchronized
    override var batteryLevel = Float.NaN

    override val hasAcceleration: Boolean = true

    @Synchronized
    fun setAcceleration(x: Float, y: Float, z: Float) {
        this.acceleration[0] = x
        this.acceleration[1] = y
        this.acceleration[2] = z
    }
}
