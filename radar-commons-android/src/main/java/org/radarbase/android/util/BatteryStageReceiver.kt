package org.radarbase.android.util

import android.content.Context

class BatteryStageReceiver(context: Context, var minimumLevel: Float, var reducedLevel: Float,
                           private val listener: (BatteryStage) -> Unit): SpecificReceiver {

    var stage: BatteryStage = BatteryStage.FULL
        private set

    enum class BatteryStage {
        FULL, EMPTY, REDUCED
    }

    private val batteryLevelReceiver = BatteryLevelReceiver(context) { level, isPlugged ->
        stage = when {
            isPlugged -> BatteryStage.FULL
            level <= minimumLevel -> BatteryStage.EMPTY
            level <= reducedLevel -> BatteryStage.REDUCED
            else -> BatteryStage.FULL
        }
        listener(stage)
    }

    override fun register() = batteryLevelReceiver.register()

    override fun notifyListener() = batteryLevelReceiver.notifyListener()

    override fun unregister() = batteryLevelReceiver.unregister()
}
