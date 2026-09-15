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
        Index(value = ["assetId", "sequence"], unique = true)
    ]
)
data class AssetPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val assetId: Long,
    val sequence: Int,
    val lat: Double,
    val lng: Double
)
