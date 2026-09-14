package nz.mckenzie.sprayday.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A raw GPS recording.
 *
 * Deliberately not foreign-keyed to [AssetEntity]: deleting a planned track must
 * never destroy the evidence of what was actually sprayed. [assetId] is a soft
 * link, and spray events hold the same id as a plain column.
 */
@Entity(
    tableName = "recorded_sessions",
    indices = [Index("trackId"), Index("startedAtEpochMs")]
)
data class RecordedSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** Column name kept from before the rename; the v3 migration renames it. */
    @ColumnInfo(name = "trackId")
    val assetId: Long? = null,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    /** [nz.mckenzie.sprayday.domain.recording.RecordingStatus] name. */
    val status: String,
    val distanceM: Double = 0.0,
    val durationMs: Long = 0L,
    val pointCount: Int = 0
)

/**
 * One accepted GPS fix. Written as it arrives so a crash, kill or flat battery
 * cannot lose a track.
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
