/*
 * Copyright 2017 The Hyve and King's College London
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
package org.radarbase.android.util

import java.net.MalformedURLException
import java.net.URL

/**
 * POJO representing a ServerConfig configuration.
 * Parses the config from a URL string.
 */
object ServerConfigUtil {
    private val URL_PATTERN =
        "(?:(\\w+)://)?([^:/]+)(?::(\\d+))?(/.*)?".toRegex()

    fun String.toServerConfig(isUnsafe: Boolean): org.radarbase.config.ServerConfig {
        val matcher = URL_PATTERN.matchEntire(this)
            ?: throw MalformedURLException("Cannot create URL from string $this")
        val groups = matcher.groups
        val protocol = groups[1]?.value ?: "https"
        val host = requireNotNull(groups[2]?.value) { "Cannot create URL without host name from $this" }
        val port = groups[3]?.value?.toIntOrNull() ?: -1
        val path = groups[4]?.value.toUrlPath()

        return org.radarbase.config.ServerConfig(
            URL(protocol, host, port, path)
        ).apply {
            this.isUnsafe = isUnsafe
        }
    }

    private fun String?.toUrlPath(): String {
        this ?: return "/"
        require(!contains("?")) { "Cannot set server path with query string" }
        require(!contains("#")) { "Cannot set server path with hash" }
        var newPath = trim { it <= ' ' }
        if (newPath.isEmpty()) {
            return "/"
        }
        if (newPath.first() != '/') {
            newPath = "/$newPath"
        }
        if (newPath.last() != '/') {
            newPath += '/'
        }
        newPath = newPath.replace("/../", "/")
        while ("//" in newPath) {
            newPath = newPath.replace("//", "/")
        }
        return newPath
    }
}
