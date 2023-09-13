package org.radarbase.passive.google.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class UtilKtTest {
    @Test
    fun testHealthTypesToString() {
        assertEquals(
            listOf(
                StepsRecord::class,
                ExerciseSessionRecord::class
            ),
            "Steps, ExerciseSession".toHealthConnectTypes(),
        )
    }
}
