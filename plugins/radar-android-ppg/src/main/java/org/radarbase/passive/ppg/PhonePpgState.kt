/*
 * Copyright 2018 The Hyve
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

package org.radarbase.passive.ppg

import android.os.SystemClock
import org.radarbase.android.device.BaseDeviceState

class PhonePpgState : BaseDeviceState() {
    private var recordingStarted: Long = 0
    var actionListener: OnActionListener? = null
    var stateChangeListener: OnStateChangeListener? = null

    val recordingTime: Long
        get() = SystemClock.elapsedRealtime() - recordingStarted

    fun recordingStartedNow() {
        recordingStarted = SystemClock.elapsedRealtime()
    }

    interface OnActionListener {
        fun startCamera()
        fun stopCamera()
    }

    interface OnStateChangeListener {
        fun release()
        fun acquire()
    }
}
