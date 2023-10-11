package nl.thehyve.prmt.shimmer

import org.radarbase.android.RadarService

class Shimmer1Service : ShimmerService()
class Shimmer2Service : ShimmerService()
class Shimmer3Service : ShimmerService()
class Shimmer4Service : ShimmerService()

class Shimmer1Provider(service: RadarService) : ShimmerProvider(
    service,
    Shimmer1Service::class.java,
    "Shimmer1"
)

class Shimmer2Provider(service: RadarService) : ShimmerProvider(
    service,
    Shimmer2Service::class.java,
    "Shimmer2"
)

class Shimmer3Provider(service: RadarService) : ShimmerProvider(
    service,
    Shimmer3Service::class.java,
    "Shimmer3"
)

class Shimmer4Provider(service: RadarService) : ShimmerProvider(
    service,
    Shimmer4Service::class.java,
    "Shimmer4"
)
