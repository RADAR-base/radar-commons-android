package org.radarbase.monitor.application.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.radarbase.android.IRadarBinder
import org.radarbase.android.RadarApplication.Companion.radarApp
import org.radarbase.android.storage.db.RadarApplicationDatabase
import org.radarbase.android.util.Boast
import org.radarbase.monitor.application.ApplicationState
import org.radarbase.monitor.application.ApplicationStatusProvider
import org.radarbase.monitor.application.R
import org.radarbase.monitor.application.databinding.ActivityApplicationStatusBinding
import org.radarbase.monitor.application.ui.viewmodel.ApplicationMetricsViewModel
import org.radarbase.monitor.application.ui.viewmodel.factory.ApplicationMetricsViewModelFactory
import org.radarbase.monitor.application.utils.AppRepository
import org.slf4j.LoggerFactory

class ApplicationMetricsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityApplicationStatusBinding
    private lateinit var database: RadarApplicationDatabase
    private lateinit var repository: AppRepository
    private val viewModel: ApplicationMetricsViewModel by viewModels { ApplicationMetricsViewModelFactory(repository) }

    private var statusProvider: ApplicationStatusProvider? = null
    private val state: ApplicationState?
        get() = statusProvider?.connection?.sourceState

    private val radarServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logger.debug("Service bound to ApplicationMetricsActivity")
            val radarService = service as IRadarBinder

            radarService.connections.forEach { provider ->
                if (provider is ApplicationStatusProvider) {
                    statusProvider = provider
                }
            }
            if (state == null) {
                logger.info("Can't send metrics state is null")
                Boast.makeText(this@ApplicationMetricsActivity,
                    R.string.unable_to_proceed_toast, Toast.LENGTH_SHORT).show(true)
                return
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            statusProvider = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityApplicationStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clAppPlugin)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        database = RadarApplicationDatabase.getInstance(applicationContext)
        repository = AppRepository(database)

        binding.btnSend.setOnClickListener {
            val uiManagerInterface = state?.uiManager ?: run {
                logger.warn("Can't send the application metrics, state is null")
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.Default) {
                val sourceStatusDeferred = async {
                    uiManagerInterface.sendSourceStatus()
                }
                val networkStatusDeferred = async {
                    uiManagerInterface.sendNetworkStatus()
                }
                sourceStatusDeferred.await()
                networkStatusDeferred.await()
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }

        updateStatusCounts()
    }
    
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
    }


    private fun updateStatusCounts() {
        logger.debug("ApplicationStatusDebug: Updating Status count")

        viewModel.loadSourceStatusCount().observe(this@ApplicationMetricsActivity) { sourceCount ->
            logger.debug("ApplicationStatusDebug: Source status count: $sourceCount")
            binding.tvPluginStatus.text = getString(R.string.plugin_log_count, sourceCount)
        }

        viewModel.loadNetworkStatusCount().observe(this@ApplicationMetricsActivity) { networkCount ->
            logger.debug("ApplicationStatusDebug: Network status count: $networkCount")
            binding.tvNetworkStatus.text = getString(R.string.network_log_count, networkCount)
        }
    }

    override fun onStop() {
        super.onStop()
        unbindService(radarServiceConnection)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ApplicationMetricsActivity::class.java)
    }
}