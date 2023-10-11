package nl.thehyve.prmt.shimmer.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.drawable.Animatable2.AnimationCallback
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.*
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.*
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import nl.thehyve.prmt.shimmer.R
import nl.thehyve.prmt.shimmer.databinding.FragmentShimmerPairSensorsBinding
import nl.thehyve.prmt.shimmer.ui.BluetoothDeviceState.Companion.bluetoothDevice
import org.radarbase.android.MainActivity
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceServiceConnection
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.BluetoothStateReceiver
import org.radarbase.android.util.BluetoothStateReceiver.Companion.bluetoothAdapter
import org.slf4j.LoggerFactory

class ShimmerPairFragment : Fragment() {
    private lateinit var broadcastManager: LocalBroadcastManager
    private var receiverIsRegistered: Boolean = false
    private lateinit var mNewDevicesArrayAdapter: BluetoothDeviceRecyclerViewAdapter
    private lateinit var bluetoothAdapter: BluetoothAdapter

    val viewModel by viewModels<ShimmerPairViewModel>()
    private var shimmerAnimationCallback: AnimationCallback? = null

    private lateinit var pairingForDevice: String
    private lateinit var binding: FragmentShimmerPairSensorsBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        pairingForDevice = arguments?.getString("sourceModel")
            ?: run {
                logger.error("Missing sourceModel")
                return null
            }

        broadcastManager = LocalBroadcastManager.getInstance(requireContext())

        bluetoothAdapter = requireContext().bluetoothAdapter
            ?.takeIf { it.isEnabled }
            ?: run {
                onNoBluetooth()
                return null
            }

        (activity as? MainActivity)?.let { listenForProviderUpdates(it) }

        binding = FragmentShimmerPairSensorsBinding.inflate(inflater)

        if (!checkBluetoothPermission()) return null

        mNewDevicesArrayAdapter = BluetoothDeviceRecyclerViewAdapter(requireContext(), ::pairDevice)

        return with(binding) {
            newDeviceList.adapter = mNewDevicesArrayAdapter
            newDeviceList.layoutManager = LinearLayoutManager(requireContext())
            shimmerDiscoveryStatus.visibility = GONE
            scanButton.setOnClickListener { startDiscovery() }

            root
        }
    }

    private fun listenForProviderUpdates(activity: MainActivity) {
        activity.radarService?.connections
            ?.filter { it.sourceProducer == "Shimmer" && it.isConnected }
            ?.forEach { provider ->
                val source = provider.connection.registeredSource ?: return@forEach
                val physicalId = source.attributes["physicalId"]
                    ?: source.expectedSourceName
                    ?: return@forEach

                val name = source.attributes["physicalName"]
                    ?: source.sourceName

                val device = bluetoothAdapter.getRemoteDevice(physicalId)
                viewModel.setPairedDevice(
                    provider.sourceModel,
                    bluetoothDevice(
                        device,
                        name,
                        BluetoothDeviceState.BondState.UNKNOWN,
                    )
                )
            }
    }

    private fun pairDevice(item: BluetoothDeviceState) {
        checkBluetoothPermission()
        bluetoothAdapter.cancelDiscovery()
        binding.scanButton.isEnabled = false
        viewModel.setPairedDevice(pairingForDevice, item)

        val activity = activity ?: return

        val radarService = (activity as? MainActivity)
            ?.radarService
            ?: return

        val connection = radarService.connections
            .find { it.sourceModel == pairingForDevice }
            ?.connection
            ?: return

        val selectedAddress = setOf(item.address)
        radarService.setAllowedSourceIds(connection, selectedAddress)
        connection.sourceState?.status = SourceStatusListener.Status.DISCONNECTED
        connection.restartRecording(selectedAddress)
        setFragmentResultListener(ShimmerPairDialog.RESULT_KEY) { _, result ->
            val statusOrdinal = result.getInt(ShimmerPairDialog.STATUS_KEY)
            when (SourceStatusListener.Status.values()[statusOrdinal]) {
                SourceStatusListener.Status.UNAVAILABLE -> {
                    // cancelled, restart search
                    radarService.setAllowedSourceIds(connection, setOf())
                    binding.scanButton.isEnabled = true
                    startDiscovery()
                }
                SourceStatusListener.Status.CONNECTED -> {
                    // success
                    activity.supportFragmentManager.popBackStack()
                }
                else -> {
                    // skip pairing -- don't try to reconnect now
                    radarService.setAllowedSourceIds(connection, setOf())
                    activity.supportFragmentManager.popBackStack()
                }
            }
        }
        activity.showPairDialog(connection)
    }

    private fun FragmentActivity.showPairDialog(connection: SourceServiceConnection<*>): ShimmerPairDialog {
        // DialogFragment.show() will take care of adding the fragment
        // in a transaction.  We also want to remove any currently showing
        // dialog, so make our own transaction and take care of that here.
        // DialogFragment.show() will take care of adding the fragment
        // in a transaction.  We also want to remove any currently showing
        // dialog, so make our own transaction and take care of that here.
        val transaction = supportFragmentManager.beginTransaction()
        supportFragmentManager.findFragmentByTag(ShimmerPairDialog.TAG)?.let { transaction.remove(it) }
        transaction.addToBackStack(null)
        // Create and show the dialog.
        return ShimmerPairDialog(connection).apply {
            show(transaction, ShimmerPairDialog.TAG)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launch {
            viewModel.potentialDevices.collect { potentialDevices ->
                if (!checkBluetoothPermission()) return@collect
                mNewDevicesArrayAdapter.mNewDevices = potentialDevices.values.sortedWith(
                    compareBy(
                        {
                            val name = it.name
                            when {
                                name == null -> 2
                                name.startsWith("Shimmer", ignoreCase = true) -> 0
                                else -> 1
                            }
                        },
                        { it.name },
                        { it.address },
                    )
                )
            }
        }

        if (!checkBluetoothPermission()) return
        if (!bluetoothAdapter.isEnabled) return

        receiverIsRegistered = true
        requireContext().registerReceiver(
            mReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
        )

        startDiscovery()
    }

    private fun startDiscovery() {
        if (!checkBluetoothPermission()) return
        bluetoothAdapter.bondedDevices.forEach { viewModel.addPotentialDevice(it) }
        bluetoothAdapter.startDiscovery()
        binding.shimmerDiscoveryStatus.visibility = VISIBLE
        shimmerAnimationCallback = binding.shimmerDiscoveryStatus.repeatAnimation()
    }

    override fun onDestroyView() {
        if (receiverIsRegistered) {
            try {
                requireContext().unregisterReceiver(mReceiver)
            } catch (ex: Exception) {
                logger.info("Failed to unregister receiver")
            }
        }
        try {
            bluetoothAdapter.cancelDiscovery()
        } catch (ex: SecurityException) {
            logger.debug("Cancelling bluetooth operation not allowed.")
        } catch (ex: Exception) {
            logger.warn("Cancelling bluetooth discovery failed", ex)
        }

        super.onDestroyView()
    }

    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!checkBluetoothPermission()) return
            // When discovery finds a device
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    logger.info("Found bluetooth device")
                    // Get the BluetoothDevice object from the Intent
                    val device: BluetoothDevice = intent.getParcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                    viewModel.addPotentialDevice(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    binding.shimmerDiscoveryStatus.visibility = GONE
                    binding.shimmerDiscoveryStatus.cancelAnimation(shimmerAnimationCallback)
                    if (mNewDevicesArrayAdapter.mNewDevices.isEmpty()) {
                        binding.noDevicesFound.visibility = VISIBLE
                    }
                }
            }
        }
    }

    private fun onNoBluetooth() {
        logger.warn("No bluetooth is available. Cancelling fragment")
        Toast.makeText(context, R.string.shimmer_bluetooth_not_enabled, Toast.LENGTH_SHORT).show()
        activity?.supportFragmentManager?.popBackStack()
    }

    fun checkBluetoothPermission(): Boolean {
        val hasPermissions = BluetoothStateReceiver.bluetoothPermissionList.all {
            context?.checkSelfPermission(it) == PERMISSION_GRANTED
        }

        return if (hasPermissions) {
            true
        } else {
            onNoBluetooth()
            false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ShimmerPairFragment::class.java)
    }
}
