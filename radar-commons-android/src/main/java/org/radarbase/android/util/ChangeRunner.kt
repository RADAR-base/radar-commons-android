package org.radarbase.android.util

/**
 * A cache that will execute a block if the value changes. This is simply a ChangeApplier that has
 * identical values for value and result.
 */
class ChangeRunner<T: Any>(
        initialValue: T? = null,
        comparator: T.(T?) -> Boolean = Any::equals
) : ChangeApplier<T, T>(initialValue, { it }, comparator)
