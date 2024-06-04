object PolarSenseUtils {

    @JvmStatic
    fun convertEpochPolarToUnixEpoch(epochPolar: Long): Long {
        val thirtyYearsInNanoSec = 946771200000000000
        val oneDayInNanoSec = 86400000000000

        val unixEpoch = epochPolar + thirtyYearsInNanoSec - oneDayInNanoSec

        return unixEpoch
    }
}