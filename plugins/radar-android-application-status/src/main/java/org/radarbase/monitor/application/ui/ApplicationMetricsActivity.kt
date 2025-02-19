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
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import org.radarbase.monitor.application.ui.adapter.NetworkStatusPagingAdapter
import org.radarbase.monitor.application.ui.adapter.SourceStatusPagingAdapter
import org.radarbase.monitor.application.ui.adapter.StringAdapter
import org.radarbase.monitor.application.ui.viewmodel.ApplicationMetricsViewModel
import org.radarbase.monitor.application.ui.viewmodel.factory.ApplicationMetricsViewModelFactory
import org.radarbase.monitor.application.utils.AppRepository
import org.radarbase.monitor.application.utils.LogNamesHolder
import org.slf4j.LoggerFactory

class ApplicationMetricsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityApplicationStatusBinding
    private lateinit var database: RadarApplicationDatabase
    private lateinit var repository: AppRepository
    private val viewModel: ApplicationMetricsViewModel by viewModels { ApplicationMetricsViewModelFactory(repository) }

    private var statusProvider: ApplicationStatusProvider? = null
    private val state: ApplicationState?
        get() = statusProvider?.connection?.sourceState

    private var sourceAdapter: SourceStatusPagingAdapter? = null
    private var networkAdapter: NetworkStatusPagingAdapter? = null

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

        setUpScrollView()
        
        checkForStatusCounts()
    }
    
    override fun onStart() {
        super.onStart()
        bindService(Intent(this, radarApp.radarService), radarServiceConnection, 0)
    }

    private fun setUpScrollView() {
        binding.rvAppPlugin.layoutManager = LinearLayoutManager(this)

        binding.rvAppPlugin.adapter = StringAdapter(
            this, listOf(
                LogNamesHolder.APPLICATION_PLUGIN_STATUS.alias,
                LogNamesHolder.APPLICATION_NETWORK_STATUS.alias
            )
        ) { topicClicked ->
            when (topicClicked) {
                LogNamesHolder.APPLICATION_PLUGIN_STATUS.alias -> {
                    lifecycleScope.launch {
                        val plugins = withContext(Dispatchers.IO) {
                            viewModel.loadDistinctPluginsFromSourceLogs()
                        }
                        binding.rvAppPlugin.adapter = StringAdapter(
                            this@ApplicationMetricsActivity,
                            plugins
                        ) { pluginName ->
                            if (sourceAdapter == null) {
                                sourceAdapter = SourceStatusPagingAdapter(
                                    this@ApplicationMetricsActivity,
                                ) {
                                    // Take no action when clicked on source status logs
                                }
                                networkAdapter = null
                                binding.rvAppPlugin.adapter = sourceAdapter

                                lifecycleScope.launch {
                                    viewModel.getSourceStatusForPagingData(pluginName)
                                        .collectLatest { pagingData ->
                                            sourceAdapter?.submitData(pagingData)
                                        }
                                }
                            }
                        }
                    }
                }

                LogNamesHolder.APPLICATION_NETWORK_STATUS.alias -> {
                    networkAdapter = NetworkStatusPagingAdapter(
                        this@ApplicationMetricsActivity,
                    ) {
                        // No action should be taken when clicked on network status logs
                    }

                    sourceAdapter = null
                    binding.rvAppPlugin.adapter = networkAdapter

                    lifecycleScope.launch {
                        viewModel.networkStatusPagingData.collectLatest { pagingData ->
                            if (binding.rvAppPlugin.adapter == networkAdapter) {
                                networkAdapter?.submitData(pagingData)
                            }
                        }
                    }
                }

                else -> {
                    // Do Nothing
                }
            }
        }
    }

    private fun checkForStatusCounts() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val sourceStatusDeferred = async {
                    viewModel.loadSourceStatusCount()
                }

                val networkStatusDeferred = async {
                    viewModel.loadNetworkStatusCount()
                }

                val sourceStatusCounts = sourceStatusDeferred.await()
                val networkStatusCounts = networkStatusDeferred.await()

                withContext(Dispatchers.Main) {
                    binding.tvPluginStatus.text = getString(R.string.plugin_log_count, sourceStatusCounts)
                    binding.tvNetworkStatus.text = getString(R.string.network_log_count, networkStatusCounts)
                }
            }

            delay((state?.uiStatusUpdateRate ?: 60) * 1000)
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