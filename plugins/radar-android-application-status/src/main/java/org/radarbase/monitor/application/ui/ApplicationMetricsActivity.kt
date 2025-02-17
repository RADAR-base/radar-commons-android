package org.radarbase.monitor.application.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.radarbase.android.storage.db.RadarApplicationDatabase
import org.radarbase.monitor.application.R
import org.radarbase.monitor.application.databinding.ActivityApplicationStatusBinding
import org.radarbase.monitor.application.ui.adapter.NetworkStatusPagingAdapter
import org.radarbase.monitor.application.ui.adapter.SourceStatusPagingAdapter
import org.radarbase.monitor.application.ui.adapter.StringAdapter
import org.radarbase.monitor.application.ui.viewmodel.ApplicationMetricsViewModel
import org.radarbase.monitor.application.ui.viewmodel.factory.ApplicationMetricsViewModelFactory
import org.radarbase.monitor.application.utils.AppRepository
import org.radarbase.monitor.application.utils.LogNamesHolder

class ApplicationMetricsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityApplicationStatusBinding
    private lateinit var database: RadarApplicationDatabase
    private lateinit var repository: AppRepository
    private val viewModel: ApplicationMetricsViewModel by viewModels { ApplicationMetricsViewModelFactory(repository) }


    private var sourceAdapter: SourceStatusPagingAdapter? = null
    private var networkAdapter: NetworkStatusPagingAdapter? = null

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

        setUpScrollView()
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
}