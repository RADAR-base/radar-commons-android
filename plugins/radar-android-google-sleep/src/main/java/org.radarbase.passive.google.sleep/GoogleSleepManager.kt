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

package org.radarbase.passive.google.sleep

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.SafeHandler
import org.radarbase.android.util.toPendingIntentFlag
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.google.GoogleSleepClassifyEvent
import org.radarcns.passive.google.GoogleSleepSegmentEvent
import org.radarcns.passive.google.SleepStatus
import org.slf4j.LoggerFactory

class GoogleSleepManager(context: GoogleSleepService) : AbstractSourceManager<GoogleSleepService, BaseSourceState>(context) {
    private val segmentEventTopic: DataCache<ObservationKey, GoogleSleepSegmentEvent> = createCache("android_google_sleep_segment_event", GoogleSleepSegmentEvent())
    private val classifyEventTopic: DataCache<ObservationKey, GoogleSleepClassifyEvent> = createCache("android_google_sleep_classify_event", GoogleSleepClassifyEvent())

    private val sleepBroadcastReceiver: BroadcastReceiver
    private val sleepHandler = SafeHandler.getInstance("Google Sleep", Process.THREAD_PRIORITY_BACKGROUND)
    private lateinit var sleepPendingIntent: PendingIntent

    init {
        name = context.getString(R.string.googleSleepDisplayName)
        sleepBroadcastReceiver = SleepReceiver(this)
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        sleepHandler.start()
        status = SourceStatusListener.Status.READY
        sleepHandler.execute {
            createSleepPendingIntent()
            registerForSleepData()
        }
    }

     fun sendSleepClassifyData(sleepIntent: Intent) {
        logger.info("Sleep segment event data received")
        val sleepSegmentEvents: List<SleepSegmentEvent> = SleepSegmentEvent.extractEvents(sleepIntent)
        sleepSegmentEvents.forEach { sleepSegmentEvent ->
            val time = currentTime
            val sleepStartTime: Double = sleepSegmentEvent.startTimeMillis / 1000.0
            val sleepEndTime: Double = sleepSegmentEvent.endTimeMillis / 1000.0
            val sleepDuration: Double = sleepSegmentEvent.segmentDurationMillis / 1000.0
            val sleepStatus: SleepStatus = sleepSegmentEvent.status.toSleepStatus()

            send(segmentEventTopic, GoogleSleepSegmentEvent(time, time, sleepStartTime,
            sleepEndTime, sleepDuration, sleepStatus))
        }
    }

     fun sendSleepSegmentData(sleepIntent: Intent) {
        logger.info("Sleep classify event data received")
        val sleepClassifyEvents: List<SleepClassifyEvent> = SleepClassifyEvent.extractEvents(sleepIntent)
        sleepClassifyEvents.forEach {  sleepClassifyEvent ->
            val time = sleepClassifyEvent.timestampMillis / 1000.0
            val sleepConfidence = sleepClassifyEvent.confidence
            val light = sleepClassifyEvent.light
            val motion = sleepClassifyEvent.motion

            send(classifyEventTopic, GoogleSleepClassifyEvent(time, currentTime, sleepConfidence, light, motion))
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerForSleepData() {
        if ( isPermissionGranted() ) {
            status = SourceStatusListener.Status.CONNECTING

            ActivityRecognition.getClient(service)
                .requestSleepSegmentUpdates(
                    sleepPendingIntent,
                    SleepSegmentRequest.getDefaultSleepSegmentRequest() )
                .addOnSuccessListener {
                    status = SourceStatusListener.Status.CONNECTED
                    logger.info("Successfully subscribed to sleep data")
                }
                .addOnFailureListener {exception ->
                    status = SourceStatusListener.Status.UNAVAILABLE
                    logger.error("Exception while subscribing to sleep data: $exception")
                }
        }
        else logger.warn("Permission not granted for ACTIVITY_RECOGNITION")
    }

    private fun isPermissionGranted(): Boolean {
        val permission = if ( Build.VERSION.SDK_INT >= 29 ) Manifest.permission.ACTIVITY_RECOGNITION
        else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
        return ContextCompat.checkSelfPermission(service,permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createSleepPendingIntent() {
        val intent = Intent(service, SleepReceiver::class.java)
        sleepPendingIntent = PendingIntent.getBroadcast(service, SLEEP_DATA_REQUEST_CODE, intent,
            PendingIntent.FLAG_CANCEL_CURRENT.toPendingIntentFlag(true))
        logger.info("Sleep pending intent created")
    }

    private fun unRegisterFromSleepData() {
        ActivityRecognition.getClient(service)
            .removeSleepSegmentUpdates(sleepPendingIntent)
            .addOnFailureListener {  }
    }

    override fun onClose() {
        sleepHandler.stop {
           unRegisterFromSleepData()
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GoogleSleepManager::class.java)

        private const val SLEEP_DATA_REQUEST_CODE = 1197424

        private fun Int.toSleepStatus(): SleepStatus = when (this) {
            0 -> SleepStatus.STATUS_SUCCESSFUL
            1 -> SleepStatus.STATUS_MISSING_DATA
            2 -> SleepStatus.STATUS_NOT_DETECTED
            else -> SleepStatus.UNKNOWN
        }
    }
}