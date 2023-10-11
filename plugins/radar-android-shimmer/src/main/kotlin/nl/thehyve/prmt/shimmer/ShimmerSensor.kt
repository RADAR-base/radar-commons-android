package nl.thehyve.prmt.shimmer

import com.shimmerresearch.driver.Configuration
import com.shimmerresearch.driver.ShimmerObject.*

enum class ShimmerSensor(
    val id: Int,
    val oldId: Int,
) {
    ACCELEROMETER(Configuration.Shimmer3.SENSOR_ID.SHIMMER_ANALOG_ACCEL, SENSOR_ACCEL),
    GYROSCOPE(Configuration.Shimmer3.SENSOR_ID.SHIMMER_MPU9X50_GYRO, SENSOR_GYRO),
    BATTERY(Configuration.Shimmer3.SENSOR_ID.SHIMMER_VBATT, SENSOR_BATT),
    MAGNETOMETER(Configuration.Shimmer3.SENSOR_ID.SHIMMER_LSM303_MAG, SENSOR_MAG),
    BAROMETER(Configuration.Shimmer3.SENSOR_ID.SHIMMER_BMPX80_PRESSURE, SENSOR_BMPX80),
}
