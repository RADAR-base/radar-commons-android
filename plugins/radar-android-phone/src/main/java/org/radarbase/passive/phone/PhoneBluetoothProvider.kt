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
import android.content.pm.PackageManager
import org.radarbase.android.device.BaseDeviceState
import org.radarbase.android.device.DeviceServiceProvider
import org.radarbase.passive.phone.PhoneSensorProvider.Companion.MODEL
import org.radarbase.passive.phone.PhoneSensorProvider.Companion.PRODUCER

open class PhoneBluetoothProvider : DeviceServiceProvider<BaseDeviceState>() {
    override val description: String
        get() = radarService.getString(R.string.phone_bluetooth_description)

    override val serviceClass: Class<PhoneBluetoothService> = PhoneBluetoothService::class.java

    override val displayName: String
        get() = radarService.getString(R.string.bluetooth_devices)

    override val isDisplayable: Boolean = false

    override val permissionsNeeded: List<String> = listOf(Manifest.permission.BLUETOOTH_ADMIN)

    override val featuresNeeded: List<String> = listOf(PackageManager.FEATURE_BLUETOOTH)

    override val sourceProducer: String = PRODUCER

    override val sourceModel: String = MODEL

    override val version: String = BuildConfig.VERSION_NAME
}
