package org.radarbase.android.util

/**
 * Keep track of storage levels.
 */
class StorageLevelReceiver(private val listener: ((StorageState) -> Unit)? = null ) {

    /** Latest storage level in [0.75,1]
     *  where 0.75 indicates partial, 75% of storage is full
     *  and 1 indicates complete storage is full
     */
    private var level: Float = 0.0f
        private set

    var state: StorageState = StorageState.AVAILABLE
        private set

    fun setLevel(level: Float) {
        this.level = level
        setState()
    }

    private fun setState() {
        when (level) {
            0.75f -> {
                state = StorageState.PARTIAL
            }
            1.0f -> {
                state = StorageState.FULL
            }
            else -> {
                state = StorageState.AVAILABLE
            }
        }
        listener?.let { it(state) }
    }

    enum class StorageState {
        AVAILABLE, PARTIAL, FULL
    }
}
