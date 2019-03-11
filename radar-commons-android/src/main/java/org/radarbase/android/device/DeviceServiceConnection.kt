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

package org.radarbase.android.device

import android.content.*
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.radarbase.android.RadarService
import org.radarbase.android.device.DeviceService.Companion.DEVICE_SERVICE_CLASS
import org.radarbase.android.device.DeviceService.Companion.DEVICE_STATUS_CHANGED
import org.slf4j.LoggerFactory

class DeviceServiceConnection<S : BaseDeviceState>(private val radarService: RadarService, val serviceClassName: String) : BaseServiceConnection<S>(serviceClassName) {
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DEVICE_STATUS_CHANGED) {
                if (serviceClassName == intent.getStringExtra(DEVICE_SERVICE_CLASS)) {
                    logger.info("AppSource status changed of device {}", deviceName)
                    val statusOrdinal = intent.getIntExtra(DEVICE_STATUS_CHANGED, 0)
                    deviceStatus = DeviceStatusListener.Status.values()[statusOrdinal]
                            .also { logger.info("Updated device status to {}", it) }
                            .also { radarService.deviceStatusUpdated(this@DeviceServiceConnection, it) }
                }
            }
        }
    }

    val context: Context
        get() = radarService

    override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(radarService)
                .registerReceiver(statusReceiver, IntentFilter(DEVICE_STATUS_CHANGED))

        if (!hasService()) {
            super.onServiceConnected(className, service)
            if (hasService()) {
                radarService.serviceConnected(this)
            }
        }
    }

    override fun onServiceDisconnected(className: ComponentName?) {
        val hadService = hasService()
        super.onServiceDisconnected(className)

        if (hadService) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(radarService).unregisterReceiver(statusReceiver)
            radarService.serviceDisconnected(this)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceServiceConnection::class.java)
    }
}
