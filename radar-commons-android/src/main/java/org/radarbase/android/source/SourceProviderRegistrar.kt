package org.radarbase.android.source

import org.radarbase.android.auth.*
import org.radarbase.android.auth.AuthService.Companion.RETRY_MAX_DELAY
import org.radarbase.android.auth.AuthService.Companion.RETRY_MIN_DELAY
import org.radarbase.android.util.DelayedRetry
import org.radarbase.android.util.SafeHandler
import org.slf4j.LoggerFactory
import java.io.Closeable

class SourceProviderRegistrar(
        private val authServiceBinder: AuthService.AuthServiceBinder,
        private val handler: SafeHandler,
        private val providers: List<SourceProvider<*>>,
        val onUpdate: (unregisteredProviders: List<SourceProvider<*>>, registeredProviders: List<SourceProvider<*>>) -> Unit
): LoginListener, Closeable {
    private val authRegistration: AuthService.LoginListenerRegistration = authServiceBinder.addLoginListener(this)
    private var isClosed: Boolean = false
    private val retry: MutableMap<SourceProvider<*>, Pair<DelayedRetry, SafeHandler.HandlerFuture>> = mutableMapOf()

    override fun loginSucceeded(manager: LoginManager?, authState: AppAuthState) {
        handler.execute {
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
            if (unregisteredSourceMetadata.isNotEmpty()) {
                authServiceBinder.unregisterSources(unregisteredSourceMetadata)
            }

            onUpdate(illegalProviders + unregisteredProviders, registeredProviders)
        }
    }

    private fun registerProvider(provider: SourceProvider<*>, authState: AppAuthState) {
        if (isClosed) {
            return
        }
        val sourceType = authState.sourceTypes.first { provider.matches(it, false) }
        authServiceBinder.registerSource(SourceMetadata(sourceType), { _, _ ->
            retry -= provider
        }, { ex ->
            logger.error("Failed to register source {}. Trying again", sourceType, ex)
            handler.executeReentrant {
                if (isClosed) { return@executeReentrant }
                val delay = retry[provider]?.first ?: DelayedRetry(RETRY_MIN_DELAY, RETRY_MAX_DELAY)
                val future = handler.delay(delay.nextDelay()) { registerProvider(provider, authState) }
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

    private fun resetRetry() {
        retry.values.forEach { (_, future) ->
            future.cancel()
        }
        retry.clear()
    }

    override fun close() {
        authServiceBinder.removeLoginListener(authRegistration)
        handler.executeReentrant {
            resetRetry()
            isClosed = true
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SourceProviderRegistrar::class.java)
    }
}
