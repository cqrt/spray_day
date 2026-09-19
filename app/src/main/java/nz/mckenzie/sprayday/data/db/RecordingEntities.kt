package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A raw GPS recording.
 *
 * Deliberately not foreign-keyed to [AssetEntity]: deleting a planned asset must
 * never destroy the evidence of what was actually sprayed. [assetId] is a soft
 * link, and spray events hold the same id as a plain column.
 */
@Entity(
    tableName = "recorded_sessions",
    indices = [Index("assetId"), Index("startedAtEpochMs")]
)
data class RecordedSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val assetId: Long? = null,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    /** [nz.mckenzie.sprayday.domain.recording.RecordingStatus] name. */
    val status: String,
    val distanceM: Double = 0.0,
    val durationMs: Long = 0L,
    val pointCount: Int = 0,
    /**
     * The operator's word that this pass did the other side of a line that is sprayed twice.
     *
     * The app can usually tell two passes apart by themselves - one heading each way, or one
     * each side of the line - and writes down nothing beyond the fixes when it can. When it
     * cannot, it asks, and this is the answer: the claim that the two passes over this line
     * were complementary. Stored here rather than derived, because a claim has to outlive the
     * reading of the fixes - otherwise the question would be asked again on every screen.
     */
    val bothSidesClaimed: Boolean = false
)

/**
 * One accepted GPS fix. Written as it arrives so a crash, kill or flat battery
 * cannot lose a recording.
 */
@Entity(
    tableName = "recorded_points",
    foreignKeys = [
        ForeignKey(
            entity = RecordedSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "sequence"], unique = true)
    ]
)
data class RecordedPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val sequence: Int,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val recordedAtEpochMs: Long
)

/**
 * A stretch of a pass the operator was paused for.
 *
 * Pausing stops the fixes, so a pass that carries on jumps from the last fix before the pause
 * to the first one after it. That jump is the one kind of gap in a recording that is not
 * ground the pass drove - the operator had stopped - so it is written down rather than
 * inferred later from the fixes, which cannot tell a pause from a dropped signal. Coverage
 * reads it: see [nz.mckenzie.sprayday.domain.geo.RecordingBreak].
 *
 * [toEpochMs] is null while the pass is still paused, and for a pass that was finished while
 * paused - which reads as a stop that runs to the end of the recording, as it should.
 */
@Entity(
    tableName = "recorded_breaks",
    foreignKeys = [
        ForeignKey(
            entity = RecordedSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RecordedBreakEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val fromEpochMs: Long,
    val toEpochMs: Long? = null
)
