package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A planned spraying track - the unit the operator thinks in.
 *
 * [lastSprayedAtEpochMs] is denormalised from [SprayEventEntity] so the map can
 * colour every track without joining the spray history on each frame. It is
 * maintained by the repository whenever a spray event is recorded.
 */
@Entity(
    tableName = "tracks",
    indices = [Index("active"), Index("lastSprayedAtEpochMs")]
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** Optional grouping, e.g. a block, farm or landowner. */
    val areaLabel: String? = null,
    val notes: String? = null,
    /** Target days between sprays; 120 is roughly three times a year. */
    val intervalDays: Int = DEFAULT_INTERVAL_DAYS,
    /** Used to estimate treated area: length x swath width. */
    val swathWidthM: Double? = null,
    val active: Boolean = true,
    val createdAtEpochMs: Long,
    val lastSprayedAtEpochMs: Long? = null,
    /** Cached geometry length so lists and the due engine need no point joins. */
    val lengthM: Double = 0.0
) {
    companion object {
        const val DEFAULT_INTERVAL_DAYS = 120
        /** Days before the due date at which a track turns yellow. */
        const val DEFAULT_LEAD_DAYS = 14
    }
}
