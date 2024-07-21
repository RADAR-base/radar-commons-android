import org.junit.Assert.assertEquals
import org.junit.Test
import org.radarbase.passive.polar.*

class PolarEpochConversionTest {

    @Test
    fun testConvertEpochPolarToUnixEpoch() {
        val testData = mapOf(
            774625951321350016L to 1.72131075132135E9
        )

        testData.forEach { (epochPolar, expectedUnixEpoch) ->
            val result = PolarUtils.convertEpochPolarToUnixEpoch(epochPolar)
            assertEquals(expectedUnixEpoch, result, 0.01)
        }
    }
}
