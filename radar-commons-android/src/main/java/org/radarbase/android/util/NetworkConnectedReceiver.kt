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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.*
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import org.slf4j.LoggerFactory

/**
 * Keeps track of whether there is a network connection (e.g., WiFi or Ethernet).
 */
class NetworkConnectedReceiver(private val context: Context, private val listener: ((isConnected: Boolean, hasWifiOrEthernet: Boolean) -> Unit)? = null) : SpecificReceiver {
    private val connectivityManager: ConnectivityManager? = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    var hasWifiOrEthernet: Boolean = true
        private set
    var isConnected: Boolean = true
        private set
    private var isReceiverRegistered: Boolean = false
    private var receiver: BroadcastReceiver? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    constructor(context: Context, listener: NetworkConnectedListener) : this(context, listener::onNetworkConnectionChanged)

    fun hasConnection(wifiOrEthernetOnly: Boolean): Boolean {
        return isConnected && (hasWifiOrEthernet || !wifiOrEthernetOnly)
    }

    override fun register() {
        if (connectivityManager == null) {
            logger.warn("Connectivity cannot be checked: System ConnectivityManager is unavailable.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerCallback(connectivityManager)
        } else {
            registerBroadcastReceiver(connectivityManager)
        }
    }

    override fun notifyListener() {
        listener?.let { it(isConnected, hasWifiOrEthernet) }
    }

    @Suppress("DEPRECATION")
    private fun registerBroadcastReceiver(cm: ConnectivityManager) {
        val localReceiver = receiver ?: object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == CONNECTIVITY_ACTION) {
                    val activeNetwork = cm.activeNetworkInfo
                    if (activeNetwork == null) {
                        isConnected = false
                        hasWifiOrEthernet = false
                    } else {
                        isConnected = activeNetwork.isConnected
                        val networkType = activeNetwork.type
                        hasWifiOrEthernet = networkType == TYPE_WIFI || networkType == TYPE_ETHERNET
                    }

                    notifyListener()
                }
            }
        }.also { receiver = it }

        val init = context.registerReceiver(localReceiver, IntentFilter(CONNECTIVITY_ACTION))
        isReceiverRegistered = true
        localReceiver.onReceive(context, init)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun registerCallback(cm: ConnectivityManager) {
        if (callback == null) {
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isConnected = true
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    hasWifiOrEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    notifyListener()
                }

                override fun onUnavailable() {
                    // nothing happened
                }

                override fun onLost(network: Network) {
                    isConnected = false
                    hasWifiOrEthernet = false
                    notifyListener()
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        isReceiverRegistered = true

        val network = cm.activeNetwork
        val networkInfo = network?.let { cm.getNetworkInfo(it) }
        if (networkInfo?.isConnected == true) {
            isConnected = true
            val capabilities = cm.getNetworkCapabilities(network)
            hasWifiOrEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    || capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        } else {
            isConnected = false
            hasWifiOrEthernet = false
        }
        notifyListener()
    }

    override fun unregister() {
        if (!isReceiverRegistered) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager?.unregisterNetworkCallback(callback)
        } else {
            context.unregisterReceiver(receiver)
        }
        isReceiverRegistered = false
    }

    interface NetworkConnectedListener {
        fun onNetworkConnectionChanged(isConnected: Boolean, hasWifiOrEthernet: Boolean)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NetworkConnectedReceiver::class.java)
    }
}
