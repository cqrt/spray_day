package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One DOC track kept on the phone for offline use.
 *
 * A **cache**, beside the offline imagery: a track DOC published, held so the browser can offer it
 * with no reception. It is keyed by the service's own `OBJECTID`, so downloading the same track twice
 * updates it rather than duplicating it, and its geometry travels in one JSON column because nothing
 * ever joins to a cache row - see [nz.mckenzie.sprayday.domain.doc.DocPathsJson].
 */
@Entity(
    tableName = "offline_doc_tracks",
    indices = [Index("downloadedAtEpochMs")]
)
data class OfflineDocTrackEntity(
    @PrimaryKey val objectId: Long,
    val name: String,
    val kind: String?,
    val lengthM: Double,
    /** The paths as `[lat, lng]` pairs, JSON: written once, read whole. */
    val pathsJson: String,
    val downloadedAtEpochMs: Long
)
