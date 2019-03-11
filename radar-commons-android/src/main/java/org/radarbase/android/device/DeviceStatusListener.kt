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

/** Listen for updates of wearable devices.  */
interface DeviceStatusListener {
    enum class Status {
        /** A device is found and the device manager is trying to connect to it. No data is yet received.  */
        CONNECTING,
        /** A device was disconnected and will no longer stream data. If this status is passed without an argument, the device manager is no longer active.  */
        DISCONNECTED,
        /** A compatible device was found and connected to. Data can now stream in.  */
        CONNECTED,
        /** A device manager is scanning for compatible devices. This status is passed without an argument.  */
        READY
    }

    /**
     * A device has an updated status.
     *
     * If the status concerns the entire system state, null is
     * passed as deviceManager.
     */
    fun deviceStatusUpdated(deviceManager: DeviceManager<*>, status: Status)

    /**
     * A device was found but it was not compatible.
     *
     * No further action is required, but the user can be informed that the connection has failed.
     * @param name human-readable device name.
     */
    fun deviceFailedToConnect(name: String)
}
