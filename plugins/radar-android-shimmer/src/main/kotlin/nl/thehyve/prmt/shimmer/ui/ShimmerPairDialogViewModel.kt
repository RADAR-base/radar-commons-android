package nl.thehyve.prmt.shimmer.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.radarbase.android.source.SourceStatusListener

class ShimmerPairDialogViewModel : ViewModel() {
    val status = MutableStateFlow(SourceStatusListener.Status.DISCONNECTED)
}
