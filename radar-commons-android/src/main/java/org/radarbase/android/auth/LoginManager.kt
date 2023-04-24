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

package org.radarbase.android.auth

import androidx.annotation.Keep
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.producer.AuthenticationException

/** Manage a single login method.  */
@Keep
interface LoginManager {
    /**
     * Types of authentication sources that the current login manager can handle.
     * @return non-empty list of source types.
     */
    val sourceTypes: List<String>

    /**
     * Register a source.
     * @param authState authentication state
     * @param source source metadata to resgister
     * @param success callback to call on success
     * @param failure callback to call on failure
     * @return true if the current LoginManager can handle the registration, false otherwise.
     * @throws AuthenticationException if the manager cannot log in
     */
    @Throws(AuthenticationException::class)
    fun registerSource(authState: AppAuthState, source: SourceMetadata,
                       success: (AppAuthState, SourceMetadata) -> Unit,
                       failure: (Exception?) -> Unit): Boolean

    fun configure(config: SingleRadarConfiguration)

    fun updateSource(appAuth: AppAuthState, source: SourceMetadata, success: (AppAuthState, SourceMetadata) -> Unit, failure: (Exception?) -> Unit): Boolean

    companion object {
        /** HTTP basic authentication.  */
        const val AUTH_TYPE_UNKNOWN = 0
        /** HTTP bearer token.  */
        const val AUTH_TYPE_BEARER = 1
        /** HTTP basic authentication.  */
        const val AUTH_TYPE_HTTP_BASIC = 2
    }
}
