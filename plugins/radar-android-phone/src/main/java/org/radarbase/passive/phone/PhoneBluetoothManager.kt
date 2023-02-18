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

package org.radarbase.passive.phone

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.BluetoothStateReceiver.Companion.bluetoothAdapter
import org.radarbase.android.util.HashGenerator
import org.radarbase.android.util.OfflineProcessor
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.phone.PhoneBluetoothDevices
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class PhoneBluetoothManager(service: PhoneBluetoothService) : AbstractSourceManager<PhoneBluetoothService, BaseSourceState>(service) {
    private val processor: OfflineProcessor
    private val bluetoothDevicesTopic: DataCache<ObservationKey, PhoneBluetoothDevices> = createCache("android_phone_bluetooth_devices", PhoneBluetoothDevices())
    private val bluetoothScannedTopic: DataCache<ObservationKey, PhoneBluetoothDevicesScanned> = createCache("android_phone_bluetooth_device_scanned", PhoneBluetoothDevicesScanned())
    private var bluetoothBroadcastReceiver: BroadcastReceiver? = null
    private val hashgenerator = HashGenerator(service,"bluetooth_devices")


    init {
        name = service.getString(R.string.bluetooth_devices)
        processor = OfflineProcessor(service) {
            process = listOf(this@PhoneBluetoothManager::processBluetoothDevices)
            requestCode = SCAN_DEVICES_REQUEST_CODE
            requestName = ACTION_SCAN_DEVICES
            wake = true
        }
    }

    override fun start(acceptableIds: Set<String>) {
        status = SourceStatusListener.Status.READY
        register()
        processor.start()
        status = SourceStatusListener.Status.CONNECTED
    }

    private fun processBluetoothDevices() {
        val bluetoothAdapter = service.bluetoothAdapter
        if (bluetoothAdapter == null) {
            logger.error("Bluetooth is not available.")
            return
        }
        if (bluetoothAdapter.isEnabled) {
            val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_SCAN
            } else Manifest.permission.BLUETOOTH_ADMIN
            if (ActivityCompat.checkSelfPermission(service, scanPermission) != PackageManager.PERMISSION_GRANTED) {
                logger.error("Cannot initiate Bluetooth scan without scan permissions")
                return
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }

            bluetoothBroadcastReceiver = object : BroadcastReceiver() {
                private var numberOfDevices: Int = 0

                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action ?: return
                    when (action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            numberOfDevices++
                             val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                             val macAddress = device?.address ?: ""
                             val hash = hashgenerator.createHash(macAddress)
                             val time = currentTime
                             val timeReceived = time
                             val macAddressHash = hash
                            send(bluetoothScannedTopic, PhoneBluetoothDevicesScanned.Builder().apply {
                                time
                                timeReceived
                                macAddressHash
                            }.build())
                        }

                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            service.unregisterReceiver(this)
                            bluetoothBroadcastReceiver = null

                            val hasConnectPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                                || ActivityCompat.checkSelfPermission(service, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

                            val bondedDevices = if (hasConnectPermission) bluetoothAdapter.bondedDevices.size else -1

                            if (!isClosed) {
                                val time = currentTime
                                send(bluetoothDevicesTopic, PhoneBluetoothDevices(
                                        time, time, bondedDevices, numberOfDevices, true))
                            }
                        }
                    }
                }
            }

            service.registerReceiver(bluetoothBroadcastReceiver, filter)
            bluetoothAdapter.startDiscovery()
        } else {
            val time = currentTime
            send(bluetoothDevicesTopic, PhoneBluetoothDevices(
                    time, time, null, null, false))
        }
    }

    override fun onClose() {
        processor.close()
        bluetoothBroadcastReceiver?.let {
            try {
                service.unregisterReceiver(it)
            } catch (ex: IllegalStateException) {
                logger.warn("Bluetooth receiver already unregistered in broadcast")
            }
            bluetoothBroadcastReceiver = null
        }
    }

    internal fun setCheckInterval(checkInterval: Long, intervalUnit: TimeUnit) {
        processor.interval(checkInterval, intervalUnit)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PhoneBluetoothManager::class.java)

        private const val SCAN_DEVICES_REQUEST_CODE = 3248902
        private const val ACTION_SCAN_DEVICES = "org.radarbase.passive.phone.PhoneBluetoothManager.ACTION_SCAN_DEVICES"
    }
}
