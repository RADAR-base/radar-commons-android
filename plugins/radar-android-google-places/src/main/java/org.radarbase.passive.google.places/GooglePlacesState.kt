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

package org.radarbase.passive.google.places

import org.radarbase.android.source.BaseSourceState
import java.util.concurrent.atomic.AtomicBoolean

class GooglePlacesState : BaseSourceState() {

    val placesClientCreated = AtomicBoolean(false)
    val fromBroadcast = AtomicBoolean(false)
    val shouldFetchPlaceId = AtomicBoolean(false)
    val shouldFetchAdditionalInfo = AtomicBoolean(false)

    @get: Synchronized
    @set: Synchronized
    var limitByPlacesLikelihood: Double = -1.0

    @get: Synchronized
    @set: Synchronized
    var limitByPlacesCount: Int = -1

}