package nl.thehyve.prmt.shimmer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import nl.thehyve.prmt.shimmer.ui.ShimmerPairFragment
import org.radarbase.android.RadarService
import org.radarbase.android.source.BaseSourceState
import org.radarbase.android.source.SourceProvider
import org.slf4j.LoggerFactory

abstract class ShimmerProvider(
    radarService: RadarService,
    override val serviceClass: Class<out ShimmerService>,
    sourceModel: String,
) : SourceProvider<BaseSourceState>(radarService) {
    override val featuresNeeded = listOf(
        PackageManager.FEATURE_BLUETOOTH,
        PackageManager.FEATURE_BLUETOOTH_LE,
    )

    override val displayName: String
        get() = radarService.getString(R.string.shimmerDisplayName)

    @Suppress("CanBePrimaryConstructorProperty")
    override val sourceModel: String = sourceModel

    override val permissionsNeeded: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
    override val pluginNames: List<String> = listOf(
        sourceModel,
        "shimmer"
    )
    override val sourceProducer: String = "Shimmer"
    override val version: String = "1.0.0"
    override val actions by lazy {
        listOf(
            Action(radarService.getString(R.string.shimmer_action_label, sourceModel)) {
                val mainFragment = supportFragmentManager.findFragmentByTag("main_fragment")

                val containerId = (mainFragment?.view?.parent as? View)?.id
                    ?: run {
                        logger.error("Cannot find main fragment to replace")
                        return@Action
                    }

                supportFragmentManager.commit {
                    setReorderingAllowed(true)
                    replace<ShimmerPairFragment>(
                        containerId,
                        "shimmer_pair",
                        bundleOf(
                            "sourceModel" to sourceModel,
                        )
                    )
                    addToBackStack("shimmer_pair")
                }
            }
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ShimmerProvider::class.java)
    }
}
