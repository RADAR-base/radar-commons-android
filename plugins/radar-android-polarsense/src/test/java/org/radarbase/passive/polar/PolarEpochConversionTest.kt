import org.junit.Assert.assertEquals
import org.junit.Test
import org.radarbase.passive.polar.*

class PolarEpochConversionTest {

    @Test
    fun testConvertEpochPolarToUnixEpoch() {
        val testData = mapOf(
            768408772990080000L to 1715093572990080000L,
            768408772997784576L to 1715093572997784576L,
            768408773005489152L to 1715093573005489152L,
            768408773013193728L to 1715093573013193728L,
            768408773020898176L to 1715093573020898176L,
            768408773028602752L to 1715093573028602752L,
            768408773036307328L to 1715093573036307328L,
            768408773044011776L to 1715093573044011776L
        )

        testData.forEach { (epochPolar, expectedUnixEpoch) ->
            val result = PolarUtils.convertEpochPolarToUnixEpoch(epochPolar)
            assertEquals(expectedUnixEpoch, result)
        }
    }
}
