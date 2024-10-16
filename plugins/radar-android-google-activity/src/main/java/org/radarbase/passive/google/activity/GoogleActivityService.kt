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

package org.radarbase.passive.google.activity

import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceService

class GoogleActivityService : SourceService<BaseSourceState>() {

    override val defaultState: BaseSourceState
        get() = BaseSourceState()

    override fun createSourceManager(): GoogleActivityManager = GoogleActivityManager(this)

    override val isBluetoothConnectionRequired: Boolean = false
}