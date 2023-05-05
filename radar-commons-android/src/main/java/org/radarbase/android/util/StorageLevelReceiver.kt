package org.radarbase.android.util

/**
 * Keep track of storage levels.
 */
class StorageLevelReceiver( storageStage: StorageStage = StorageStage(0.75f,1.0f),
    private val listener: ((StorageState, String) -> Unit)? = null ) {

    /** Latest storage level in [0.75,1]
     *  where 0.75 - 1 indicates partial, more than
     *  75% storage is full but not completely full,
     *  and 1 indicates complete storage is full
     */
    var level: Float = 0.0f
        set(value) {
            field = value
            updateState()
        }

    var topic: String = " "
        set(value) {
        field = value }

    var state: StorageState = StorageState.AVAILABLE
        private set

    private val _storageStage = ChangeRunner(storageStage)

    private fun updateState() {
       state = when(level){
           1.0f -> StorageState.FULL
           in 0.75f .. 1.0f -> StorageState.PARTIAL
           else -> StorageState.AVAILABLE
       }
    }

    var storageStage: StorageStage
    get() = _storageStage.value.copy()
    set(value){
        _storageStage.applyIfChanged(value.copy()) { listener?.let { it(state, topic) } }
    }

    enum class StorageState {
        AVAILABLE, PARTIAL, FULL
    }
}
