package org.radarbase.android.auth

/** Manages persistence of the AppAuthState. */
interface AuthSerializer {
    /**
     * Load the state from this serialization.
     * @return state if it was stored, null otherwise.
     */
    fun load(): AppAuthState?
    /**
     * Store the auth state to this serialization.
     */
    fun store(state: AppAuthState)
    /**
     * Remove the auth state from this serialization.
     */
    fun remove()
}
