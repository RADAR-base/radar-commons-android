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

package org.radarcns.android.device

import android.os.Bundle
import org.radarcns.android.kafka.ServerStatusListener
import org.radarcns.data.RecordData
import java.io.IOException

interface DeviceServiceBinder<T : BaseDeviceState> {
    /** Get the current device status  */
    val deviceStatus: T?
    /** Get the current device name, or null if unknown.  */
    val deviceName: String?
    /** Get the current server status  */
    val serverStatus: ServerStatusListener.Status
    /** Get the last number of records sent  */
    val serverRecordsSent: Map<String, Int>

    /** Start scanning and recording from a compatible device.
     * @param acceptableIds a set of source IDs that may be connected to.
     * If empty, no selection is made.
     */
    fun startRecording(acceptableIds: Set<String>)

    /** Stop scanning and recording  */
    fun stopRecording()

    @Throws(IOException::class)
    fun getRecords(topic: String, limit: Int): RecordData<Any, Any>?

    /** Update the configuration of the service  */
    fun updateConfiguration(bundle: Bundle)

    /** Number of records in cache unsent  */
    val numberOfRecords: Long?

    fun needsBluetooth(): Boolean

    fun shouldRemainInBackground(): Boolean
}
