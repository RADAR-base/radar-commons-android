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

package org.radarbase.android.source

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import org.radarbase.android.RadarService

class SourceServiceConnection<S : BaseSourceState>(
    private val radarService: RadarService,
    serviceClassName: String,
) : BaseServiceConnection<S>(serviceClassName) {
    val context: Context
        get() = radarService

    override fun onServiceConnected(className: ComponentName?, service: IBinder?) {
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
            radarService.serviceDisconnected(this)
        }
    }
}
