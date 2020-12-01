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

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.source.SourceStatusListener.Status.*
import org.radarbase.passive.ppg.PhonePpgService.Companion.PPG_MEASUREMENT_TIME_DEFAULT
import org.radarbase.passive.ppg.PhonePpgService.Companion.PPG_MEASUREMENT_TIME_NAME

class PhonePpgActivity : AppCompatActivity(), Runnable {
    private lateinit var mTextField: TextView
    private var ppgProvider: PhonePpgProvider? = null
    private lateinit var handler: Handler
    private var wasConnected: Boolean = false
    private lateinit var startButton: Button

    private val state: PhonePpgState?
        get() = ppgProvider?.connection?.sourceState

    private val radarServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val radarService = service as IRadarBinder
            ppgProvider = null
            for (provider in radarService.connections) {
                if (provider is PhonePpgProvider) {
                    ppgProvider = provider
                    state?.stateChangeListener?.acquire()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            state?.stateChangeListener?.release()
            ppgProvider = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.phone_ppg_activity)

        startButton = findViewById(R.id.startButton)
        startButton.setOnClickListener {
            val deviceData = state ?: return@setOnClickListener
            val actionListener = deviceData.actionListener ?: return@setOnClickListener
            if (deviceData.status == DISCONNECTED || deviceData.status == READY) {
                actionListener.startCamera()
            } else {
                actionListener.stopCamera()
            }
        }

        val ml = findViewById<View>(R.id.phonePpgFragmentLayout)
        ml.bringToFront()

        val config = (application as RadarApplication).configuration
        val totalTime = config.latestConfig.getInt(PPG_MEASUREMENT_TIME_NAME, PPG_MEASUREMENT_TIME_DEFAULT.toInt())
        this.findViewById<TextView>(R.id.ppgMainDescription).text = resources.getQuantityString(
                R.plurals.ppgMainDescription, totalTime, totalTime)
        mTextField = findViewById(R.id.ppgMeasurementStatus)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar).apply {
            setTitle(R.string.ppg_app)
        })

        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }
        wasConnected = false

        handler = Handler(Looper.getMainLooper())
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(this, 50)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(this)
    }

    override fun onStop() {
        super.onStop()
        unbindService(radarServiceConnection)
    }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onBackPressed() {
        state?.actionListener?.stopCamera()
        super.onBackPressed()
    }

    override fun run() {
        val state = state
        if (state != null && state.status == CONNECTED) {
            val recordingTime = (state.recordingTime / 1000L).toInt()
            mTextField.text = resources.getQuantityString(
                    R.plurals.ppg_recording_seconds, recordingTime, recordingTime)
            startButton.setText(R.string.ppg_stop)
            wasConnected = true
        } else if (wasConnected) {
            mTextField.setText(R.string.ppg_done)
            startButton.setText(R.string.start)
        } else {
            mTextField.setText(R.string.ppg_not_started)
            startButton.setText(R.string.start)
        }
        handler.postDelayed(this, 50)
    }
}
