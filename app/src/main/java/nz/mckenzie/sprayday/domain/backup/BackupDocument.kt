package nz.mckenzie.sprayday.domain.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod

/**
 * Everything the operator would lose if the phone went in the creek.
 *
 * These records mirror the database rather than the screens, because a backup that
 * cannot be restored exactly is worse than no backup: it looks like safety and is
 * not. Ids are carried through so the links between an asset, its geometry, its sprays
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
    val groups: List<GroupRecord> = emptyList(),
    /**
     * Written as `assets` since format 2. [JsonNames] keeps files written before that
     * readable: a v1 file called this list `tracks`, and its assets come back as tracks
     * with no spray method recorded rather than being refused.
     */
    @JsonNames("tracks")
    val assets: List<AssetRecord> = emptyList(),
    val sprayEvents: List<SprayEventRecord> = emptyList(),
    /** Called `trackDefaults` before format 2, like everything else with "track" in it. */
    @JsonNames("trackDefaults")
    val assetDefaults: List<AssetDefaultRecord> = emptyList(),
    val recordings: List<RecordingRecord> = emptyList(),
    /**
     * The switches, so a restored phone comes back set up rather than factory-fresh.
     *
     * Optional and added without a version bump: a file written before this existed simply
     * has no such key, and the switches stay as the new device has them. The LINZ key and
     * the GitHub token are deliberately not in here - see [BackupSettingsRecord].
     */
    val settings: BackupSettingsRecord? = null
) {
    companion object {
        const val FORMAT = "spray-day-backup"

        /**
         * Raised only when the shape changes in a way an older app cannot read.
         *
         * Version 2 added groups, the kind, shape and method of an asset, and the group
         * an asset belongs to. Adding fields is not such a change - unknown keys are
         * ignored and missing ones fall back to their defaults - so a v1 file restores
         * into this build. The reverse is not true: a v2 file holds group rows, so it
         * needs a build that has groups.
         */
        const val VERSION = 2
    }
}

/** A named collection of assets, e.g. "Estuary" holding the road, the lagoon and the lower track. */
@Serializable
data class GroupRecord(
    val id: Long,
    val name: String,
    val notes: String? = null
)

/** A planned asset, with the geometry that gives it a length. */
@Serializable
data class AssetRecord(
    val id: Long,
    val name: String,
    /**
     * The pre-2 free-text "block or area". Read when an older file is restored, and
     * turned into a group of that name; always null in a file this build writes, which
     * carries [groupId] and the [BackupDocument.groups] instead.
     */
    val areaLabel: String? = null,
    /** The group this asset belongs to, as written in [BackupDocument.groups]. */
    val groupId: Long? = null,
    /** [AssetKind] name. A pre-2 file has none, and every asset in it was a track. */
    val kind: String = AssetKind.TRACK.name,
    /** [AssetShape] name. A pre-2 file holds lines only. */
    val shape: String = AssetShape.LINE.name,
    /** [SprayMethod] name, and [SprayMethod.UNSET] for anything a pre-2 file holds. */
    val method: String = SprayMethod.UNSET.name,
    val notes: String? = null,
    val intervalDays: Int,
    val swathWidthM: Double? = null,
    /**
     * How many passes along the line the job takes: one, or two.
     *
     * Optional and added without a version bump, like the settings and the pauses before it: a
     * file written before this existed has no such key, and an asset restored from one takes the
     * default - one pass, which is what a job meant then. The number is
     * [nz.mckenzie.sprayday.data.db.AssetEntity.DEFAULT_PASSES_REQUIRED] and its neighbours, kept
     * in step by a test rather than imported, because a backup is read by an app that may be
     * older or newer than the one that wrote it.
     */
    val passesRequired: Int = 1,
    /**
     * How far apart the two passes run, in metres, or null when nobody has said. See
     * [nz.mckenzie.sprayday.data.db.AssetEntity.passSeparationM].
     */
    val passSeparationM: Double? = null,
    val active: Boolean = true,
    val createdAtEpochMs: Long,
    val lastSprayedAtEpochMs: Long? = null,
    val lengthM: Double = 0.0,
    val points: List<LinePointRecord> = emptyList(),
    /**
     * The side tracks hanging off [points], in the order they were drawn.
     *
     * Optional and added without a version bump, like the two-pass fields and the pauses before it: a
     * file written before side tracks existed has no such key, and an asset restored from one has one
     * path - which is what a track was then. The first path is [points] and this holds the rest, which
     * is the storage's own shape (`asset_points.pathIndex`), so a file and a database agree about what
     * a track is without either having to explain itself.
     *
     * **A file written by a newer build and read by an older one loses these** - the format's oldest
     * rule is that a key a build does not know is ignored, and this key is one an older build does not
     * know. That is the trade the rule makes, and the same one v2's groups made.
     */
    val spurs: List<List<LinePointRecord>> = emptyList()
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

/** A spray of one asset, with what went out on it. */
@Serializable
data class SprayEventRecord(
    val id: Long,
    /** Called `trackId` before format 2. */
    @JsonNames("trackId")
    val assetId: Long,
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

/** The amounts an asset is pre-filled with next time. */
@Serializable
data class AssetDefaultRecord(
    /** Called `trackId` before format 2. */
    @JsonNames("trackId")
    val assetId: Long,
    val productId: Long,
    val defaultQuantityMl: Double? = null
)

/** A GPS recording, with every accepted fix. */
@Serializable
data class RecordingRecord(
    val id: Long,
    val name: String,
    /** Called `trackId` before format 2. */
    @JsonNames("trackId")
    val assetId: Long? = null,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val status: String,
    val distanceM: Double = 0.0,
    val durationMs: Long = 0L,
    val pointCount: Int = 0,
    /**
     * The operator's word that this pass did the other side of a line sprayed twice. Optional and
     * added without a version bump: a file written before this existed has no such key, and a
     * restored pass with no claim is read as evidence alone, which is what it was.
     * See [nz.mckenzie.sprayday.data.db.RecordedSessionEntity.bothSidesClaimed].
     */
    val bothSidesClaimed: Boolean = false,
    val points: List<RecordedPointRecord> = emptyList(),
    /**
     * The stretches the pass was paused for. Optional and added without a version bump: a file
     * written before pauses were kept simply has no such key, and a restored recording with no
     * pauses is read exactly as the app read it before - a gap in its fixes was ground the pass
     * drove, which is what a gap is.
     */
    val breaks: List<RecordedBreakRecord> = emptyList()
)

/**
 * One pause in a recording.
 *
 * [toEpochMs] is null for a pass that was finished while it was paused, which reads as a stop
 * running to the end of the recording.
 */
@Serializable
data class RecordedBreakRecord(
    val fromEpochMs: Long,
    val toEpochMs: Long? = null
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
