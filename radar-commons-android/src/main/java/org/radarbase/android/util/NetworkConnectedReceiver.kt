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

package org.radarbase.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import org.slf4j.LoggerFactory

/**
 * Keeps track of whether there is a network connection (e.g., WiFi or Ethernet).
 */
class NetworkConnectedReceiver(context: Context, private val listener: ((NetworkState) -> Unit)? = null) : SpecificReceiver {
    private val connectivityManager: ConnectivityManager? = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
    private var isReceiverRegistered: Boolean = false
    private val callback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            state = NetworkState(isConnected = true, hasWifiOrEthernet = state.hasWifiOrEthernet)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state = NetworkState(state.isConnected, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

        override fun onUnavailable() {
            // nothing happened
        }

        override fun onLost(network: Network) {
            state = NetworkState(isConnected = false, hasWifiOrEthernet = false)
        }
    }

    constructor(context: Context, listener: NetworkConnectedListener) : this(context, listener::onNetworkConnectionChanged)

    private val _state = ChangeRunner(NetworkState(isConnected = false, hasWifiOrEthernet = false))

    var state: NetworkState
        get() = _state.value
        private set(value) {
            _state.applyIfChanged(value) { notifyListener() }
        }

    fun hasConnection(wifiOrEthernetOnly: Boolean) = state.hasConnection(wifiOrEthernetOnly)

    override fun register() {
        connectivityManager ?: run {
            logger.warn("Connectivity cannot be checked: System ConnectivityManager is unavailable.")
            return
        }
        registerCallback(connectivityManager)
    }

    override fun notifyListener() {
        listener?.invoke(state)
    }

    private fun registerCallback(cm: ConnectivityManager) {
        val network = cm.activeNetwork
        val networkInfo = network?.let { cm.getNetworkInfo(it) }
        state = if (networkInfo?.isConnected == true) {
            val capabilities = cm.getNetworkCapabilities(network)
            NetworkState(true,
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                            || capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
        } else {
            NetworkState(isConnected = false, hasWifiOrEthernet = false)
        }

        cm.registerDefaultNetworkCallback(callback)
        isReceiverRegistered = true
    }

    override fun unregister() {
        if (!isReceiverRegistered) {
            return
        }
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (ex: Exception) {
            logger.debug("Skipping unregistered receiver: {}", ex.toString())
        }
        isReceiverRegistered = false
    }

    data class NetworkState(val isConnected: Boolean, val hasWifiOrEthernet: Boolean) {
        fun hasConnection(wifiOrEthernetOnly: Boolean): Boolean =
            isConnected && (hasWifiOrEthernet || !wifiOrEthernetOnly)
    }

    interface NetworkConnectedListener {
        fun onNetworkConnectionChanged(state: NetworkState)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NetworkConnectedReceiver::class.java)
    }
}
