package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A downloaded offline imagery area.
 *
 * The tiles themselves live on the filesystem (see the offline tile store); this
 * row is the *record* of what was downloaded, so the UI can list areas, show
 * progress and delete them. Progress columns exist so an interrupted download can
 * be resumed honestly rather than restarted.
 */
@Entity(
    tableName = "offline_areas",
    indices = [Index("createdAtEpochMs")]
)
data class OfflineAreaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double,
    val minZoom: Int,
    val maxZoom: Int,
    /** Tiles the plan says this area needs. */
    val plannedTiles: Long,
    /** Tiles actually on disk. */
    val downloadedTiles: Long = 0L,
    val bytes: Long = 0L,
    val createdAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
    /** Why the last attempt stopped early, if it did. */
    val lastError: String? = null
) {
    val isComplete: Boolean get() = completedAtEpochMs != null

    val percent: Int
        get() = when {
            plannedTiles <= 0L -> 0
            isComplete -> 100
            else -> ((downloadedTiles * 100) / plannedTiles).toInt().coerceIn(0, 99)
        }
}
