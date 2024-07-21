object PolarUtils {

    @JvmStatic
    fun convertEpochPolarToUnixEpoch(epochPolar: Long): Double {
        val thirtyYearsInNanoSec = 946771200000000000
        val oneDayInNanoSec = 86400000000000

        val unixEpoch = (epochPolar + thirtyYearsInNanoSec - oneDayInNanoSec).toDouble()

        return unixEpoch/1000_000_000L
    }
}