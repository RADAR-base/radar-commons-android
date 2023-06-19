package org.radarbase.passive.google.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.IntermenstrualBleedingRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SleepStageRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider
import java.lang.String.CASE_INSENSITIVE_ORDER
import java.util.*
import kotlin.reflect.KClass

class HealthConnectProvider(radarService: RadarService) :
    SourceProvider<BaseSourceState>(radarService) {
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

    companion object {
        const val HEALTH_CONNECT_DATA_TYPES = "health_connect_data_types"

        fun String.toHealthConnectTypes(): List<KClass<out Record>> = split(" ,+")
            .mapNotNull { healthConnectTypes[it.trim()] }

        val healthConnectTypes = TreeMap<String, KClass<out Record>>(CASE_INSENSITIVE_ORDER).apply {
            put("ActiveCaloriesBurned", ActiveCaloriesBurnedRecord::class)
            put("BasalBodyTemperature", BasalBodyTemperatureRecord::class)
            put("BasalMetabolicRate", BasalMetabolicRateRecord::class)
            put("BloodGlucose", BloodGlucoseRecord::class)
            put("BloodPressure", BloodPressureRecord::class)
            put("BodyFat", BodyFatRecord::class)
            put("BodyTemperature", BodyTemperatureRecord::class)
            put("BodyWaterMass", BodyWaterMassRecord::class)
            put("BoneMass", BoneMassRecord::class)
            put("CervicalMucus", CervicalMucusRecord::class)
            put("CyclingPedalingCadence", CyclingPedalingCadenceRecord::class)
            put("Distance", DistanceRecord::class)
            put("ElevationGained", ElevationGainedRecord::class)
            put("ExerciseSession", ExerciseSessionRecord::class)
            put("FloorsClimbed", FloorsClimbedRecord::class)
            put("HeartRate", HeartRateRecord::class)
            put("HeartRateVariabilityRmssd", HeartRateVariabilityRmssdRecord::class)
            put("Height", HeightRecord::class)
            put("Hydration", HydrationRecord::class)
            put("IntermenstrualBleeding", IntermenstrualBleedingRecord::class)
            put("LeanBodyMass", LeanBodyMassRecord::class)
            put("MenstruationFlow", MenstruationFlowRecord::class)
            put("MenstruationPeriod", MenstruationPeriodRecord::class)
            put("Nutrition", NutritionRecord::class)
            put("OvulationTest", OvulationTestRecord::class)
            put("OxygenSaturation", OxygenSaturationRecord::class)
            put("Power", PowerRecord::class)
            put("RespiratoryRate", RespiratoryRateRecord::class)
            put("RestingHeartRate", RestingHeartRateRecord::class)
            put("SexualActivity", SexualActivityRecord::class)
            put("SleepSession", SleepSessionRecord::class)
            put("SleepStage", SleepStageRecord::class)
            put("Speed", SpeedRecord::class)
            put("StepsCadence", StepsCadenceRecord::class)
            put("Steps", StepsRecord::class)
            put("TotalCaloriesBurned", TotalCaloriesBurnedRecord::class)
            put("Vo2Max", Vo2MaxRecord::class)
            put("Weight", WeightRecord::class)
            put("WheelchairPushes", WheelchairPushesRecord::class)
        }
    }
}
