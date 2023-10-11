package nl.thehyve.prmt.shimmer

import org.radarbase.android.source.BaseSourceState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

class ShimmerSourceState : BaseSourceState() {
    override var batteryLevel: Float = Float.NaN

    companion object {
        val currentManagerIdx = AtomicLong(0)
        val activeDevices: ConcurrentMap<String, Pair<Long, KClass<out ShimmerService>>> = ConcurrentHashMap()
    }
}
