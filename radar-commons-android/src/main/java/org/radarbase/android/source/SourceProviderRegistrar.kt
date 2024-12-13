package org.radarbase.android.source

import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.AuthService
import org.radarbase.android.auth.AuthService.Companion.RETRY_MAX_DELAY
import org.radarbase.android.auth.AuthService.Companion.RETRY_MIN_DELAY
import org.radarbase.android.auth.LoginListener
import org.radarbase.android.auth.LoginManager
import org.radarbase.android.auth.SourceMetadata
import org.radarbase.android.util.CoroutineTaskExecutor
import org.radarbase.android.util.DelayedRetry
import org.slf4j.LoggerFactory
import java.io.Closeable

class SourceProviderRegistrar(
    private val authServiceBinder: AuthService.AuthServiceBinder,
    private val service: LifecycleService,
    private val providers: List<SourceProvider<*>>,
    val onUpdate: suspend (unregisteredProviders: List<SourceProvider<*>>, registeredProviders: List<SourceProvider<*>>) -> Unit
): LoginListener {

    private val authRegistration: Deferred<AuthService.LoginListenerRegistry> = service.lifecycleScope.async(Dispatchers.Default) {
        authServiceBinder.addLoginListener(this@SourceProviderRegistrar)
    }

    private var isClosed: Boolean = false
    private val retry: MutableMap<SourceProvider<*>, Pair<DelayedRetry, Job>> = mutableMapOf()
    private val retryAccessMutex: Mutex = Mutex()

    init {
        service.lifecycleScope.launch {
            authRegistration.await()
        }
    }

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        if (isClosed) {
            return
        }
        service.lifecycleScope.launch(Dispatchers.Default) {
            logger.debug("Received login succeeded callback in registrar")
            resetRetry()

            val (legalProviders, illegalProviders) = providers.partition { it.canRegisterFor(authState, false) }
            val (registeredProviders, unregisteredProviders) = legalProviders.partition { it.isRegisteredFor(authState, false) }

            val unregisteredSourceMetadata = mutableListOf<SourceMetadata>()
            unregisteredProviders.forEach { provider ->
                val unregistered = authState.sourceMetadata.filter { provider.matches(it.type, false) }
                if (unregistered.isEmpty()) {
                    registerProvider(provider, authState)
                } else {
                    unregisteredSourceMetadata += unregistered
                }
            }
            logger.trace("Unregistered SourceMetadata: {}", unregisteredSourceMetadata.map { it.sourceName } )
            if (unregisteredSourceMetadata.isNotEmpty()) {
                authServiceBinder.unregisterSources(unregisteredSourceMetadata)
            }
            logger.debug("Updating caller about providers")
            onUpdate(illegalProviders + unregisteredProviders, registeredProviders)
        }
    }

    private suspend fun registerProvider(provider: SourceProvider<*>, authState: AppAuthState) {
        if (isClosed) {
            return
        }
        logger.debug("Registering provider: {}", provider.pluginName)
        val sourceType = authState.sourceTypes.first { provider.matches(it, false) }
        authServiceBinder.registerSource(SourceMetadata(sourceType), { _, _ ->
            retry -= provider
        }, { ex ->
            logger.error("Failed to register source {}. Trying again", sourceType, ex)
            service.lifecycleScope.launch(Dispatchers.Default) {
                if (isClosed) { return@launch }
                val delay = retry[provider]?.first ?: DelayedRetry(RETRY_MIN_DELAY, RETRY_MAX_DELAY)
                val future = service.lifecycleScope.launch(Dispatchers.Default) {
                    delay(delay.nextDelay())
                    registerProvider(provider, authState)
                }
                if (future != null) {
                    retry[provider] = Pair(delay, future)
                } else {
                    retry -= provider
                }
            }
        })
    }

    override fun loginFailed(manager: LoginManager?, ex: Exception?) {
    }

    override suspend fun logoutSucceeded(manager: LoginManager?, authState: AppAuthState) = Unit

    private fun resetRetry() {
        logger.info("Resetting retry values")
        try {
            retry.values.forEach { (_, future) ->
                future.cancel()
            }
        } catch (ex: Exception) {
            logger.warn("Exception when resetting retry values", ex)
        }
        retry.clear()
    }

    fun stop() {
        logger.debug("Stopping the source provider registrar")
        service.lifecycleScope.launch {
            authServiceBinder.removeLoginListener(authRegistration.await())
        }
        resetRetry()
        isClosed = true
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SourceProviderRegistrar::class.java)
    }
}
