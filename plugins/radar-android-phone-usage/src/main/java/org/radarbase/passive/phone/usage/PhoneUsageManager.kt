/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.passive.phone.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.*
import android.app.usage.UsageStatsManager
import android.content.*
import android.os.Build
import android.util.SparseArray
import org.radarbase.android.data.DataCache
import org.radarbase.android.device.AbstractDeviceManager
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceStatusListener
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.PhoneInteractionState
import org.radarcns.passive.phone.PhoneUsageEvent
import org.radarcns.passive.phone.PhoneUserInteraction
import org.radarcns.passive.phone.UsageEventType
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class PhoneUsageManager(context: PhoneUsageService) : AbstractDeviceManager<PhoneUsageService, BaseDeviceState>(context) {

    private val usageEventTopic: DataCache<ObservationKey, PhoneUsageEvent>?
    private val userInteractionTopic: DataCache<ObservationKey, PhoneUserInteraction> = createCache("android_phone_user_interaction", PhoneUserInteraction())

    private val phoneStateReceiver: BroadcastReceiver
    private val usageStatsManager: UsageStatsManager?
    private val preferences: SharedPreferences
    private val phoneUsageProcessor: OfflineProcessor

    private var lastPackageName: String? = null
    private var lastTimestamp: Long = 0
    private var lastEventType: Int = 0
    private var lastEventIsSent: Boolean = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            this.usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager?
        } else {
            this.usageStatsManager = null
        }

        if (usageStatsManager != null) {
            usageEventTopic = createCache("android_phone_usage_event", PhoneUsageEvent())
        } else {
            logger.warn("Usage statistics are not available.")
            usageEventTopic = null
        }
        this.preferences = context.getSharedPreferences(PhoneUsageService::class.java.name, Context.MODE_PRIVATE)
        this.loadLastEvent()

        // Listen for screen lock/unlock events
        phoneStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                // If previous event was a shutdown, then this action indicates that the phone has booted
                if (preferences.getString(LAST_USER_INTERACTION, "") == Intent.ACTION_SHUTDOWN) {
                    sendInteractionState(ACTION_BOOT)
                }

                if (intent == null || intent.action == null) {
                    return
                }

                sendInteractionState(intent.action!!)
            }
        }

        phoneUsageProcessor = OfflineProcessor(context) {
            process = listOf(this@PhoneUsageManager::processUsageEvents)
            requestCode = USAGE_EVENT_REQUEST_CODE
            requestName = ACTION_UPDATE_EVENTS
            wake = false
        }
    }

    override fun start(acceptableIds: Set<String>) {
        updateStatus(DeviceStatusListener.Status.READY)
        // Start query of usage events
        phoneUsageProcessor.start()
        register()

        // Activity to perform when alarm is triggered
        service.registerReceiver(phoneStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT) // unlock
            addAction(Intent.ACTION_SCREEN_OFF) // lock
            addAction(Intent.ACTION_SHUTDOWN) // shutdown
        })

        updateStatus(DeviceStatusListener.Status.CONNECTED)
    }

    private fun sendInteractionState(action: String) {
        val state: PhoneInteractionState = when (action) {
            Intent.ACTION_SCREEN_OFF -> PhoneInteractionState.STANDBY
            Intent.ACTION_USER_PRESENT -> PhoneInteractionState.UNLOCKED
            Intent.ACTION_SHUTDOWN -> PhoneInteractionState.SHUTDOWN
            ACTION_BOOT -> PhoneInteractionState.BOOTED
            else -> return
        }

        val time = currentTime
        send(userInteractionTopic, PhoneUserInteraction(time, time, state))

        // Save the last user interaction state. Value shutdown is used to register boot.
        preferences.edit()
                .putString(LAST_USER_INTERACTION, action)
                .apply()
        logger.info("Interaction State: {} {}", time, state)
    }

    /**
     * Set the interval in which to collect logs about app usage.
     * @param interval collection interval in seconds
     */
    fun setUsageEventUpdateRate(interval: Long, unit: TimeUnit) {
        phoneUsageProcessor.interval(interval, unit)
        logger.info("Usage event alarm activated and set to a period of {} seconds", interval)
    }

    private fun processUsageEvents() {
        if (usageStatsManager == null) {
            return
        }

        // Get events from previous event to now or from a fixed history
        val usageEvents = usageStatsManager.queryEvents(lastTimestamp, System.currentTimeMillis())

        // Loop through all events, send opening and closing of app
        // Assume events are ordered on timestamp in ascending order (old to new)
        val event = UsageEvents.Event()
        while (usageEvents.getNextEvent(event) && !phoneUsageProcessor.isDone) {
            // Ignore config changes, old events, and events from the same activity
            if (event.eventType == UsageEvents.Event.CONFIGURATION_CHANGE || event.timeStamp < lastTimestamp) {
                continue
            }

            if (event.packageName == lastPackageName) {
                updateLastEvent(event, false)
            } else {
                // send this closing event
                if (lastPackageName != null && !lastEventIsSent) {
                    sendLastEvent()
                }

                updateLastEvent(event, true)

                // Send the opening of new event
                sendLastEvent()
            }
        }

        // Store the last previous event on internal memory for the next run
        this.storeLastEvent()
    }

    private fun sendLastEvent() {
        // Event type conversion to Schema defined
        val usageEventType = when (lastEventType) {
            MOVE_TO_FOREGROUND -> UsageEventType.FOREGROUND
            MOVE_TO_BACKGROUND -> UsageEventType.BACKGROUND
            CONFIGURATION_CHANGE -> UsageEventType.CONFIG
            SHORTCUT_INVOCATION_COMPAT -> UsageEventType.SHORTCUT
            USER_INTERACTION_COMPAT -> UsageEventType.INTERACTION
            else -> UsageEventType.OTHER
        }

        val time = lastTimestamp / 1000.0
        val value = PhoneUsageEvent(time, currentTime, lastPackageName, null, null, usageEventType)
        send(usageEventTopic!!, value)

        if (logger.isDebugEnabled) {
            logger.debug("Event: [{}] {}\n\t{}", lastEventType, lastPackageName, Date(lastTimestamp))
        }
    }

    private fun updateLastEvent(event: UsageEvents.Event, isSent: Boolean) {
        lastPackageName = event.packageName
        lastTimestamp = event.timeStamp
        lastEventType = event.eventType
        lastEventIsSent = isSent
    }

    private fun storeLastEvent() {
        preferences.edit()
                .putString(LAST_PACKAGE_NAME, lastPackageName)
                .putLong(LAST_EVENT_TIMESTAMP, lastTimestamp)
                .putInt(LAST_EVENT_TYPE, lastEventType)
                .putBoolean(LAST_EVENT_IS_SENT, lastEventIsSent)
                .apply()
    }

    private fun loadLastEvent() {
        lastPackageName = preferences.getString(LAST_PACKAGE_NAME, null)
        lastTimestamp = preferences.getLong(LAST_EVENT_TIMESTAMP, System.currentTimeMillis())
        lastEventType = preferences.getInt(LAST_EVENT_TYPE, 0)
        lastEventIsSent = preferences.getBoolean(LAST_EVENT_IS_SENT, true)

        if (lastPackageName == null) {
            logger.info("No previous event details stored")
        }
    }

    @Throws(IOException::class)
    override fun close() {
        phoneUsageProcessor.close()
        val context = service
        context.unregisterReceiver(phoneStateReceiver)
        super.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhoneUsageManager::class.java)

        private val EVENT_TYPES = SparseArray<UsageEventType>(6)

        init {
            EVENT_TYPES.append(UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEventType.FOREGROUND)
            EVENT_TYPES.append(UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEventType.BACKGROUND)
            EVENT_TYPES.append(UsageEvents.Event.CONFIGURATION_CHANGE, UsageEventType.CONFIG)
            if (android.os.Build.VERSION.SDK_INT >= 25) {
                EVENT_TYPES.append(UsageEvents.Event.SHORTCUT_INVOCATION, UsageEventType.SHORTCUT)
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                EVENT_TYPES.append(UsageEvents.Event.USER_INTERACTION, UsageEventType.INTERACTION)
            }
        }

        private val SHORTCUT_INVOCATION_COMPAT = if (android.os.Build.VERSION.SDK_INT >= 25) SHORTCUT_INVOCATION else 8
        private val USER_INTERACTION_COMPAT = if (android.os.Build.VERSION.SDK_INT >= 23) USER_INTERACTION else 7

        private const val LAST_PACKAGE_NAME = "org.radarcns.phone.packageName"
        private const val LAST_EVENT_TIMESTAMP = "org.radarcns.phone.timestamp"
        private const val LAST_EVENT_TYPE = "org.radarbase.passive.phone.usage.PhoneUsageManager.lastEventType"
        private const val LAST_EVENT_IS_SENT = "org.radarbase.passive.phone.usage.PhoneUsageManager.lastEventIsSent"
        private const val LAST_USER_INTERACTION = "org.radarcns.phone.lastAction"
        private const val ACTION_BOOT = "org.radarcns.phone.ACTION_BOOT"
        private const val ACTION_UPDATE_EVENTS = "org.radarbase.passive.phone.usage.PhoneUsageManager.ACTION_UPDATE_EVENTS"
        private const val USAGE_EVENT_REQUEST_CODE = 586106
    }
}
