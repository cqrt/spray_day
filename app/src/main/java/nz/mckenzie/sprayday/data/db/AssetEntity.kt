package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod

/**
 * Something the operator sprays: a track, a road, or a piece of infrastructure.
 *
 * The word is deliberately wider than "track". The same list now holds a stopbank to
 * blanket, an estuary road to spot-spray and a picnic table to wipe over, and those
 * differ only in [kind], [method] and whether they are a line or a place.
 *
 * [lastSprayedAtEpochMs] is denormalised from [SprayEventEntity] so the map can colour
 * every asset without joining the spray history on each frame. It is maintained by the
 * repository whenever a spray event is recorded.
 */
@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("active"), Index("lastSprayedAtEpochMs"), Index("groupId")]
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** [AssetKind] name. Stored as text so an unknown value cannot break the map. */
    val kind: String = AssetKind.TRACK.name,
    /** [AssetShape] name: a line, or a single point. */
    val shape: String = AssetShape.LINE.name,
    /** [SprayMethod] name. [SprayMethod.UNSET] until the operator says otherwise. */
    val method: String = SprayMethod.UNSET.name,
    /** The group this asset is worked with, if any. Null is "on its own". */
    val groupId: Long? = null,
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

        /** Days before the due date at which an asset turns yellow. */
        const val DEFAULT_LEAD_DAYS = 14
    }
}
