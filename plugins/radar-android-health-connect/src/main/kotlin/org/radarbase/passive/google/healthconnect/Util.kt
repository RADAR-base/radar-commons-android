package org.radarbase.passive.google.healthconnect

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.health.connect.client.HealthConnectClient
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
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass

// TODO: Available from version 1.1.0-alpha01
// HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE
internal fun Context.isHealthConnectAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            HealthConnectClient.sdkStatus(this) == HealthConnectClient.SDK_AVAILABLE

internal fun String.toHealthConnectTypes(): List<KClass<out Record>> = split(' ', ',', '+')
    .mapNotNull { healthConnectTypes[it.trim()] }

internal val healthConnectTypes = TreeMap<String, KClass<out Record>>(java.lang.String.CASE_INSENSITIVE_ORDER).apply {
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

internal val healthConnectNames: Map<KClass<out Record>, String> = healthConnectTypes.entries.associateBy(
    Map.Entry<String, KClass<out Record>>::value,
    Map.Entry<String, KClass<out Record>>::key,
)

@RequiresApi(Build.VERSION_CODES.O)
internal fun Instant.toDouble(): Double {
    val floatingNano = nano.toBigDecimal().setScale(7) / 1_000_000_000.toBigDecimal()
    return (epochSecond.toBigDecimal() + floatingNano).toDouble()
}
