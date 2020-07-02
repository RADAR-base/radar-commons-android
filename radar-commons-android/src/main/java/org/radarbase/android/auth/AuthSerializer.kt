package org.radarbase.android.auth

interface AuthSerializer {
    fun load(): AppAuthState?
    fun store(state: AppAuthState)
    fun remove()
}
