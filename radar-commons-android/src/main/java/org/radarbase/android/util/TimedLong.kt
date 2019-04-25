package org.radarbase.android.util

class TimedLong(value: Long) {
    @get:Synchronized
    var time = System.currentTimeMillis()
        private set

    @get:Synchronized
    @set:Synchronized
    var value: Long = value
        set(value) {
            field = value
            time = System.currentTimeMillis()
        }
}
