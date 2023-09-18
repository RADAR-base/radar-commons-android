package org.radarbase.passive.google.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoField

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

    @Test
    fun instantToDouble() {
        val instant = Instant.ofEpochSecond(1700000000)
            .with(ChronoField.NANO_OF_SECOND, 123_456_789)

        // Note: a double cannot represent the last two digits.
        assertEquals(1.7000000001234567e9, instant.toDouble(), 0.00000000001)
        assertEquals("1.7000000001234567E9", instant.toDouble().toString())

    }
}
