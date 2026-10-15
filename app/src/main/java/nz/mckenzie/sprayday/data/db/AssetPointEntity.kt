package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One vertex of a planned asset's geometry, in order.
 *
 * Stored as rows rather than an encoded blob so geometry can be re-filtered or
 * simplified later without a data migration. An asset that is a place rather than a
 * path has exactly one of these; a line has as many as the operator drew.
 *
 * **A row knows which path it belongs to.** [pathIndex] 0 is the line itself and 1 and up are the
 * side tracks hanging off it, in the order they were drawn; [sequence] counts within one path. That
 * is what lets a track be a line with a spur into the gully rather than one path walked up the spur
 * and back down - which counted those metres twice and read the doubling as a second pass. The
 * unique index is per path, so two paths may both start at the same junction vertex.
 *
 * [altitudeM] is the height the file gave this point, when a file gave one. It is nullable because a
 * line drawn on a map has no height - a tap gives two numbers, not three - so "no height" is what a
 * drawn point honestly is, and it must not be written down as zero. See docs/elevation.md.
 */
@Entity(
    tableName = "asset_points",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("assetId"),
        Index(value = ["assetId", "pathIndex", "sequence"], unique = true)
    ]
)
data class AssetPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val assetId: Long,
    val pathIndex: Int = 0,
    val sequence: Int,
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null
)
