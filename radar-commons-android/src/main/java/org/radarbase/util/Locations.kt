package org.radarbase.util

import android.location.Location
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Locations {
    private const val METERS_PER_DEGREE_AT_EQUATOR = 111_300.0

    fun Location.withRandomShift(maxDistanceMeters: Double): Location =
        if (maxDistanceMeters <= 0.0) {
            this
        } else {
            Location(this).apply { randomShift(maxDistanceMeters) }
        }

    fun Location.randomShift(maxDistanceMeters: Double) {
        if (maxDistanceMeters <= 0.0) {
            return
        }
        val u = ThreadLocalRandom.current().nextDouble(1.0)
        val t = ThreadLocalRandom.current().nextDouble(2 * PI)

        val randomDistance = sqrt(u) * maxDistanceMeters / METERS_PER_DEGREE_AT_EQUATOR

        longitude += randomDistance * sin(t) * cos(latitude.toRadians())
        latitude += randomDistance * cos(t)
    }

    private fun Double.toRadians() = this * PI / 180.0
}
