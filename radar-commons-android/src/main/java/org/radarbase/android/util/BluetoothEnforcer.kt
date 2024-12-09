package org.radarbase.android.util

import android.app.Activity.RESULT_OK
import android.bluetooth.BluetoothAdapter
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.ENABLE_BLUETOOTH_REQUESTS
import org.radarbase.android.RadarServiceStateReactor
import org.radarbase.android.util.BluetoothStateReceiver.Companion.bluetoothIsEnabled
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class BluetoothEnforcer(
    private val context: ComponentActivity,
    private val radarConnection: ManagedServiceConnection<IRadarBinder>,
    private val serviceBoundActions: MutableList<RadarServiceStateReactor>
) {
    private var isRequestingBluetooth = false
    private val config = context.radarConfig
    private val enableBluetoothRequests: ChangeRunner<Boolean>
    private val bluetoothIsNeeded = ChangeRunner(false)
    private lateinit var bluetoothNeededRegistration: Job

    private val bluetoothStateReceiver: BluetoothStateReceiver

    var isEnabled: Boolean
        get() = enableBluetoothRequests.value
        set(value) {
            enableBluetoothRequests.applyIfChanged(value) { enableRequests ->
                config.put(ENABLE_BLUETOOTH_REQUESTS, enableRequests)
                context.lifecycleScope.launch {
                    config.persistChanges()
                }
                if (bluetoothIsNeeded.value) {
                    bluetoothStateReceiver.register()
                } else {
                    bluetoothStateReceiver.unregister()
                }
            }
        }

    private val prefs =
        context.getSharedPreferences("org.radarbase.android.util.BluetoothEnforcer", MODE_PRIVATE)

    init {
        val latestConfig = config.latestConfig
        val lastRequest = prefs.getLong(LAST_REQUEST, 0L)
        val cooldown = TimeUnit.SECONDS.toMillis(
            latestConfig.getLong(BLUETOOTH_REQUEST_COOLDOWN, TimeUnit.DAYS.toSeconds(3))
        )
        if (lastRequest + cooldown < System.currentTimeMillis()) {
            context.lifecycleScope.launch {
                config.reset(ENABLE_BLUETOOTH_REQUESTS)
            }
        }

        serviceBoundActions += {
            updateNeedsBluetooth(it.needsBluetooth())
        }
        enableBluetoothRequests = ChangeRunner(
            latestConfig.getBoolean(ENABLE_BLUETOOTH_REQUESTS, true)
        )

        bluetoothStateReceiver = BluetoothStateReceiver(context) { enabled ->
            if (!enabled) requestEnableBt()
        }
    }

    fun start() {
        testBindBluetooth()

        context.lifecycleScope.launch {
            context.repeatOnLifecycle(Lifecycle.State.STARTED) {
                (context as RadarApplication).radarServiceImpl.actionBluetoothNeeded.collectLatest {
                    testBindBluetooth()
                }
            }
        }
    }

    fun stop() {
        bluetoothNeededRegistration.cancel()
        bluetoothIsNeeded.applyIfChanged(false) {
            bluetoothStateReceiver.unregister()
        }
    }

    private fun updateNeedsBluetooth(value: Boolean) {
        bluetoothIsNeeded.applyIfChanged(value) { doesNeedBluetooth ->
            if (doesNeedBluetooth && isEnabled) {
                bluetoothStateReceiver.register()
                requestEnableBt()
            } else {
                bluetoothStateReceiver.unregister()
            }
        }
    }

    private fun testBindBluetooth() {
        context.lifecycleScope.launch {
            radarConnection.applyBinder {
                updateNeedsBluetooth(needsBluetooth())
            }
        }
    }

    /**
     * Sends an intent to request bluetooth to be turned on.
     */
    private fun requestEnableBt() {
        context.lifecycleScope.launch {
            if (isRequestingBluetooth) {
                return@launch
            }
            context.lifecycleScope.launch {
                isRequestingBluetooth = true
                delay(1000)
                if (isEnabled && !context.bluetoothIsEnabled) {
                    withContext(Dispatchers.IO) {
                        prefs.edit()
                            .putLong(LAST_REQUEST, System.currentTimeMillis())
                            .commit()
                    }
                    try {
                        context.startActivityForResult(Intent().apply {
                            action = BluetoothAdapter.ACTION_REQUEST_ENABLE
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }, REQUEST_ENABLE_BT)
                    } catch (ex: SecurityException) {
                        logger.warn("Cannot request Bluetooth to be enabled - no permission")
                    }
                }
                isRequestingBluetooth = false
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode == REQUEST_ENABLE_BT) {
            isEnabled = resultCode == RESULT_OK
        }
    }

    companion object {
        const val REQUEST_ENABLE_BT: Int = 6944
        private const val LAST_REQUEST: String = "lastRequest"
        private const val BLUETOOTH_REQUEST_COOLDOWN = "bluetooth_request_cooldown"

        private val logger = LoggerFactory.getLogger(BluetoothEnforcer::class.java)
    }
}
