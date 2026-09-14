package nz.mckenzie.sprayday.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One vertex of a planned track's geometry, in order.
 *
 * Stored as rows rather than an encoded blob so geometry can be re-filtered or
 * simplified later without a data migration.
 */
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("trackId"),
        Index(value = ["trackId", "sequence"], unique = true)
    ]
)
data class AssetPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Column name kept from before the rename; the v3 migration renames it. */
    @ColumnInfo(name = "trackId")
    val assetId: Long,
    val sequence: Int,
    val lat: Double,
    val lng: Double
)
