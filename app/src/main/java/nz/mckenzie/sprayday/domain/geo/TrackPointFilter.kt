package nz.mckenzie.sprayday.domain.geo

/**
 * Thresholds used to decide whether a raw GPS fix is good enough to append to a
 * recorded track. Defaults are tuned for spraying tracks from a vehicle or on
 * foot: fixes worse than 30 m are dropped, points closer than 3 m are treated as
 * jitter, and implausibly fast jumps are rejected.
 */
data class PointFilterConfig(
    val minDistanceM: Double = 3.0,
    val maxAccuracyM: Float = 30f,
    val maxSpeedMps: Double = 40.0
)

/**
 * Stateful filter that decides which incoming fixes are kept.
 *
 * The first acceptable fix is always kept. Counters are exposed so the tracking
 * UI can explain *why* a track looks sparse (e.g. "12 fixes rejected: poor
 * accuracy"), which is far more useful than silently dropping data.
 */
class TrackPointFilter(
    val config: PointFilterConfig = PointFilterConfig()
) {
    private var lastAccepted: GeoPoint? = null

    var acceptedCount: Int = 0
        private set
    var rejectedAccuracyCount: Int = 0
        private set
    var rejectedDistanceCount: Int = 0
        private set
    var rejectedSpeedCount: Int = 0
        private set

    val lastAcceptedPoint: GeoPoint? get() = lastAccepted

    fun reset() {
        lastAccepted = null
        acceptedCount = 0
        rejectedAccuracyCount = 0
        rejectedDistanceCount = 0
        rejectedSpeedCount = 0
    }

    /**
     * Carries the filter on from a recording that already exists.
     *
     * Used when collection starts again against a session that has fixes behind it -
     * the process was killed mid-spray, or the screen the operator was watching was
     * re-created. The first fix after that is compared against the ground actually
     * covered, and the point count goes on from where the recording got to, rather
     * than restarting at one on a track that is already half recorded.
     */
    fun seed(points: List<GeoPoint>) {
        lastAccepted = points.lastOrNull()
        acceptedCount = points.size
    }

    /** @return true if the fix was accepted and should be persisted. */
    fun accept(candidate: GeoPoint): Boolean {
        val accuracy = candidate.accuracyM
        if (accuracy != null && accuracy > config.maxAccuracyM) {
            rejectedAccuracyCount++
            return false
        }

        val last = lastAccepted
        if (last == null) {
            markAccepted(candidate)
            return true
        }

        val distance = haversineMeters(last.lat, last.lng, candidate.lat, candidate.lng)
        if (distance < config.minDistanceM) {
            rejectedDistanceCount++
            return false
        }

        val elapsedSeconds = (candidate.timeMs - last.timeMs) / 1000.0
        if (elapsedSeconds > 0.0 && distance / elapsedSeconds > config.maxSpeedMps) {
            rejectedSpeedCount++
            return false
        }

        markAccepted(candidate)
        return true
    }

    private fun markAccepted(point: GeoPoint) {
        lastAccepted = point
        acceptedCount++
    }
}
