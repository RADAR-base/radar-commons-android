package org.radarbase.android.util

/**
 * A local cache for a value and its result after transformation.
 * This assumes that the transformation is completely functional.
 * @param T value type
 * @param V result type
 * @param initialValue initial value. If non-null, no errors will be generated when value or result
 *                     is called without being called before.
 * @param applier transformation from value to result. It is only called if no result is cached, or
 *                the previously passed value is not equal to the currently passed value.
 * @param comparator how to determine whether two values are equal. Uses equals method by default.
 */
open class ChangeApplier<T: Any, V: Any>(
        initialValue: T?,
        private val applier: (T) -> V,
        private val comparator: T.(T?) -> Boolean = Any::equals
) {
    private var _value: T? = null

    /**
     * Last value that was passed.
     * @throws IllegalStateException if no value has been passed yet.
     */
    @get:Synchronized
    val value: T
        get() = checkNotNull(_value) { "Value is not initialized yet" }

    /**
     * Last result that was generated.
     * @throws UninitializedPropertyAccessException if no result has been computed yet.
     */
    @get:Synchronized
    lateinit var lastResult: V
        private set

    constructor(
            applier: (T) -> V,
            comparator: T.(T?) -> Boolean = Any::equals
    ) : this(null, applier, comparator)

    init {
        if (initialValue != null) doApply(initialValue)
    }

    @Synchronized
    private fun doApply(value: T): V {
        val result = applier(value)
        synchronized(this) {
            _value = value
            lastResult = result
        }
        return result
    }

    /**
     * Set a new value and computes the result. If no change was made to the value, no computation
     * is performed but the last result is returned. Only if the value is newly computed, an
     * optional block is run on the result.
     */
    fun applyIfChanged(value: T, block: ((V) -> Unit)? = null): V {
        return if (!isSame(value)) {
            doApply(value).also {
                if (block != null) block(it)
            }
        } else lastResult
    }

    /** Whether given value matches the currently cached value. */
    @Synchronized
    fun isSame(value: T): Boolean = value.comparator(_value)
}
