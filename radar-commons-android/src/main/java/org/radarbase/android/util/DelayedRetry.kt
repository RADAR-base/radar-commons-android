package org.radarbase.android.util

import kotlin.random.Random

class DelayedRetry(
        private val minDelay: Long,
        private val maxDelay: Long,
) {
    private var currentDelay: Long? = null

    fun nextDelay(): Long = (2 * (currentDelay ?: minDelay))
            .coerceAtMost(maxDelay)
            .also { currentDelay = it }
            .let { Random.nextLong(minDelay, it) }

    fun reset() {
        currentDelay = null
    }
}
