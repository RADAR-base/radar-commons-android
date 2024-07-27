package org.radarbase.android.config

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.radarbase.android.RadarConfiguration
import org.radarbase.android.RadarConfiguration.Companion.BASE_URL_KEY
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_DEFAULT
import org.radarbase.android.RadarConfiguration.Companion.FETCH_TIMEOUT_MS_KEY
import org.radarbase.android.RadarConfiguration.Companion.PROJECT_ID_KEY
import org.radarbase.android.RadarConfiguration.Companion.USER_ID_KEY
import org.radarbase.android.RadarConfiguration.RemoteConfigStatus.*
import org.radarbase.android.auth.AppAuthState
import org.radarbase.android.auth.portal.GetSubjectParser.Companion.externalUserId
import org.radarbase.android.auth.portal.GetSubjectParser.Companion.humanReadableUserId
import org.slf4j.LoggerFactory

class CombinedRadarConfig(
    private val localConfig: LocalConfig,
    private val remoteConfigs: List<RemoteConfig>,
    defaultsFactory: () -> Map<String, String>,
): RadarConfiguration {
    private val defaults = defaultsFactory()
            .filterValues { it.isNotEmpty() }
    @Volatile
    override var status: RadarConfiguration.RemoteConfigStatus = INITIAL

    @Volatile
    override var latestConfig: SingleRadarConfiguration = readConfig()
    override val config: MutableLiveData<SingleRadarConfiguration> = MutableLiveData()

    init {
        config.postValue(latestConfig)

        remoteConfigs.forEach { remoteConfig ->
            remoteConfig.onStatusUpdateListener = { newStatus ->
                logger.info("Got updated status {}", newStatus)

                updateStatus()

                if (newStatus == FETCHED) {
                    updateConfig()
                }
            }
        }
    }

    private fun updateStatus() {
        val allStatus = remoteConfigs.map { it.status }
        status = when {
            FETCHING in allStatus -> FETCHING
            FETCHED in allStatus && allStatus.all { it == FETCHED || it == UNAVAILABLE } -> FETCHED
            FETCHED in allStatus || PARTIALLY_FETCHED in allStatus -> PARTIALLY_FETCHED
            ERROR in allStatus -> ERROR
            READY in allStatus -> READY
            INITIAL in allStatus -> INITIAL
            else -> UNAVAILABLE
        }
    }

    private fun updateConfig() {
        val newConfig = readConfig()
        if (newConfig != latestConfig) {
            if (newConfig.status != latestConfig.status) {
                logger.info("Updating config status to {}", newConfig.status)
            }
            if (newConfig.config != latestConfig.config) {
                logger.info("Updating config to {}", newConfig)
            }
            latestConfig = newConfig
            config.postValue(newConfig)
            remoteConfigs.forEach { it.updateWithConfig(newConfig) }
        } else {
            logger.info("No change to config. Skipping.")
        }
    }

    override fun put(key: String, value: Any): String? = localConfig.put(key, value)

    override fun persistChanges() {
        if (localConfig.persistChanges()) {
            updateConfig()
        }
    }

    private fun readConfig() = SingleRadarConfiguration(status, HashMap<String, String>().apply {
        this += defaults
        remoteConfigs.forEach {
            this += it.cache
        }
        this += localConfig.config
    })

    override fun reset(vararg keys: String) {
        localConfig -= keys
        persistChanges()
    }

    override fun resetFirebaseRemoteConfigs() {
        (remoteConfigs.find { it is FirebaseRemoteConfiguration } as FirebaseRemoteConfiguration).resetConfigs()
        status = INITIAL
    }

    override fun fetch() = remoteConfigs.forEach {
        it.fetch(latestConfig.getLong(FETCH_TIMEOUT_MS_KEY, FETCH_TIMEOUT_MS_DEFAULT))
    }

    override fun forceFetch() = remoteConfigs.forEach {
        it.forceFetch()
    }

    override fun toString(): String = latestConfig.toString()

    override fun updateWithAuthState(context: Context, appAuthState: AppAuthState?) {
        val enableAnalytics = appAuthState?.isPrivacyPolicyAccepted == true
        logger.debug("Setting Firebase Analytics enabled: {}", enableAnalytics)
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enableAnalytics)

        appAuthState ?: return

        val baseUrl = appAuthState.baseUrl
        val projectId = appAuthState.projectId
        val userId = appAuthState.userId
        val crashlytics = FirebaseCrashlytics.getInstance()

        baseUrl?.let {
            put(BASE_URL_KEY, baseUrl)
            put(RadarConfiguration.KAFKA_REST_PROXY_URL_KEY, "$baseUrl/kafka/")
            put(RadarConfiguration.SCHEMA_REGISTRY_URL_KEY, "$baseUrl/schema/")
            put(RadarConfiguration.MANAGEMENT_PORTAL_URL_KEY, "$baseUrl/managementportal/")
            put(RadarConfiguration.OAUTH2_TOKEN_URL, "$baseUrl/managementportal/oauth/token")
            put(RadarConfiguration.OAUTH2_AUTHORIZE_URL, "$baseUrl/managementportal/oauth/authorize")
            crashlytics.setCustomKey(BASE_URL_KEY, baseUrl)
        }

        projectId?.let {
            put(PROJECT_ID_KEY, it)
            crashlytics.setCustomKey(PROJECT_ID_KEY, projectId)
        }
        userId?.let {
            put(USER_ID_KEY, it)
            put(RadarConfiguration.READABLE_USER_ID_KEY, appAuthState.humanReadableUserId ?: it)
            put(RadarConfiguration.EXTERNAL_USER_ID_KEY, appAuthState.externalUserId ?: it)
            crashlytics.setUserId(userId)
            crashlytics.setCustomKey(USER_ID_KEY, userId)
        }

        persistChanges()
        remoteConfigs.forEach {
            it.updateWithAuthState(appAuthState)
        }
        forceFetch()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CombinedRadarConfig::class.java)
    }
}
