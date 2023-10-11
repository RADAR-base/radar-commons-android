package nl.thehyve.prmt.shimmer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.thehyve.prmt.shimmer.R
import nl.thehyve.prmt.shimmer.databinding.DialogShimmerPairingBinding
import org.radarbase.android.source.SourceService
import org.radarbase.android.source.SourceServiceConnection
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.BroadcastRegistration
import org.radarbase.android.util.register
import kotlin.time.Duration.Companion.milliseconds

class ShimmerPairDialog(
    private val connection: SourceServiceConnection<*>,
) : DialogFragment() {
    private lateinit var binding: DialogShimmerPairingBinding
    private val shimmerPairViewModel: ShimmerPairDialogViewModel by viewModels()
    private lateinit var statusListener: BroadcastRegistration

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DialogShimmerPairingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.cancel.setOnClickListener {
            dismiss()
            setFragmentResult(RESULT_KEY, bundleOf(STATUS_KEY to SourceStatusListener.Status.UNAVAILABLE.ordinal))
        }
        binding.finalAction.setOnClickListener {
            dismiss()
            setFragmentResult(RESULT_KEY, bundleOf(STATUS_KEY to shimmerPairViewModel.status.value.ordinal))
        }

        lifecycleScope.launch {
            shimmerPairViewModel.status.collect { sourceStatus ->
                binding.updateStatus(sourceStatus)
            }
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(100.milliseconds)
                connection.sourceStatus?.let {
                    shimmerPairViewModel.status.value = it
                }
            }
        }
    }

    @MainThread
    private fun DialogShimmerPairingBinding.updateStatus(sourceStatus: SourceStatusListener.Status) {
        deviceStatus.setImageResource(
            when (sourceStatus) {
                SourceStatusListener.Status.CONNECTED -> R.drawable.avd_anim_connected_circle
                SourceStatusListener.Status.DISCONNECTED, SourceStatusListener.Status.DISCONNECTING -> R.drawable.baseline_circle_red_700_24dp
                SourceStatusListener.Status.READY -> R.drawable.avd_anim_ready
                SourceStatusListener.Status.CONNECTING -> R.drawable.avd_anim_connecting
                else -> R.drawable.avd_anim_ready
            }
        )
        deviceStatus.repeatAnimation()
        deviceStatusText.setText(when (sourceStatus) {
            SourceStatusListener.Status.CONNECTED -> R.string.device_connected
            SourceStatusListener.Status.DISCONNECTED, SourceStatusListener.Status.DISCONNECTING -> R.string.device_disconnected
            SourceStatusListener.Status.READY -> R.string.device_ready
            SourceStatusListener.Status.CONNECTING -> R.string.device_connecting
            else -> R.string.device_unavailable
        })
        if (sourceStatus == SourceStatusListener.Status.CONNECTED) {
            cancel.visibility = View.INVISIBLE
            finalAction.setText(R.string.done)
        }
    }

    override fun onStart() {
        super.onStart()
        statusListener = LocalBroadcastManager.getInstance(requireContext()).register(SourceService.SOURCE_STATUS_CHANGED) { _, intent ->
            if (connection.serviceClassName == intent.getStringExtra(SourceService.SOURCE_SERVICE_CLASS)) {
                val statusOrdinal = intent.getIntExtra(SourceService.SOURCE_STATUS_CHANGED, 0)
                shimmerPairViewModel.status.value = SourceStatusListener.Status.values()[statusOrdinal]
            }
        }
    }

    override fun onStop() {
        super.onStop()
        statusListener.unregister()
    }

    companion object {
        const val TAG = "shimmer_pair_dialog"
        const val RESULT_KEY = "shimmer_pair_result"
        const val STATUS_KEY = "status"
    }
}
