package nz.mckenzie.sprayday.domain.backup

import kotlinx.serialization.Serializable

/**
 * Everything the operator would lose if the phone went in the creek.
 *
 * These records mirror the database rather than the screens, because a backup that
 * cannot be restored exactly is worse than no backup: it looks like safety and is
 * not. Ids are carried through so the links between a track, its geometry, its sprays
 * and the recording that proves them survive the round trip.
 *
 * Deliberately absent: the downloaded offline areas. They describe tiles on one
 * device, the tiles themselves are re-downloadable, and a restore that claimed to
 * bring imagery back would be lying.
 */
@Serializable
data class BackupDocument(
    /** Marks the file as ours, so the wrong JSON is refused rather than guessed at. */
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAtEpochMs: Long,
    /** Which build wrote it, for a human reading the file later. */
    val appVersion: String,
    val products: List<ProductRecord> = emptyList(),
    val tracks: List<TrackRecord> = emptyList(),
    val sprayEvents: List<SprayEventRecord> = emptyList(),
    val trackDefaults: List<TrackDefaultRecord> = emptyList(),
    val recordings: List<RecordingRecord> = emptyList()
) {
    companion object {
        const val FORMAT = "spray-day-backup"

        /**
         * Raised only when the shape changes in a way an older app cannot read. Adding
         * a field is not such a change: unknown keys are ignored and missing ones fall
         * back to their defaults, so old files keep restoring into new builds.
         */
        const val VERSION = 1
    }
}

/** A planned track, with the geometry that gives it a length. */
@Serializable
data class TrackRecord(
    val id: Long,
    val name: String,
    val areaLabel: String? = null,
    val notes: String? = null,
    val intervalDays: Int,
    val swathWidthM: Double? = null,
    val active: Boolean = true,
    val createdAtEpochMs: Long,
    val lastSprayedAtEpochMs: Long? = null,
    val lengthM: Double = 0.0,
    val points: List<LinePointRecord> = emptyList()
)

/** One vertex of a planned line. */
@Serializable
data class LinePointRecord(val lat: Double, val lng: Double)

/** A product in the catalogue, archived or not. */
@Serializable
data class ProductRecord(
    val id: Long,
    val name: String,
    val unit: String = "mL",
    val rateText: String? = null,
    val notes: String? = null,
    val archived: Boolean = false
)

/** A spray of one track, with what went out on it. */
@Serializable
data class SprayEventRecord(
    val id: Long,
    val trackId: Long,
    val sprayedAtEpochMs: Long,
    val waterLitres: Double? = null,
    val operatorName: String? = null,
    val notes: String? = null,
    val distanceM: Double? = null,
    val areaSqm: Double? = null,
    val recordedSessionId: Long? = null,
    val products: List<SprayProductRecord> = emptyList()
)

/** The amount of one product used in one spray. */
@Serializable
data class SprayProductRecord(val productId: Long, val quantityMl: Double)

/** The amounts a track is pre-filled with next time. */
@Serializable
data class TrackDefaultRecord(
    val trackId: Long,
    val productId: Long,
    val defaultQuantityMl: Double? = null
)

/** A GPS recording, with every accepted fix. */
@Serializable
data class RecordingRecord(
    val id: Long,
    val name: String,
    val trackId: Long? = null,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val status: String,
    val distanceM: Double = 0.0,
    val durationMs: Long = 0L,
    val pointCount: Int = 0,
    val points: List<RecordedPointRecord> = emptyList()
)

/** One accepted GPS fix, as it was written. */
@Serializable
data class RecordedPointRecord(
    val sequence: Int,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val recordedAtEpochMs: Long
)
