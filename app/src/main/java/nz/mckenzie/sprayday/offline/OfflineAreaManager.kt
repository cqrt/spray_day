package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.data.db.OfflineAreaDao
import nz.mckenzie.sprayday.data.db.OfflineAreaEntity
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/**
 * A downloaded offline imagery area, as the screen shows it.
 *
 * [storedTiles] counts the plan's tiles that are on disk, which is not the same
 * as "tiles this download fetched": tiles browsed on the map beforehand count
 * too, and a resumed download starts from what is already there. [missingTiles]
 * is therefore the honest answer to "what is not covered", whether because LINZ
 * has no imagery for those tiles or because they failed to download.
 */
data class OfflineArea(
    val id: Long,
    val name: String,
    val plannedTiles: Int,
    val storedTiles: Int,
    val bytes: Long,
    val isComplete: Boolean,
    /** Why the last attempt stopped early, if it did. Only real failures. */
    val lastError: String? = null
) {
    /**
     * 0-100. Reports 100 only once the download has finished: an interrupted area
     * must not look done. An area that is *finished* but short of tiles LINZ has
     * no imagery for does report 100 - there is nothing left to wait for, and the
     * shortfall is reported through [missingTiles] rather than by a progress bar
     * that would sit at 99% for ever.
     */
    val percent: Int
        get() = when {
            plannedTiles <= 0 -> 0
            isComplete -> 100
            else -> ((storedTiles * 100L) / plannedTiles).toInt().coerceIn(0, 99)
        }

    val sizeLabel: String get() = formatBytes(bytes)

    val missingTiles: Int get() = (plannedTiles - storedTiles).coerceAtLeast(0)

    /** Complete, but with tiles LINZ had no imagery for. Worth saying out loud. */
    val isShortButComplete: Boolean get() = isComplete && missingTiles > 0
}

/** What is actually on the device, for the screen's summary line. */
data class TileStoreSummary(val tiles: Long, val bytes: Long) {
    val sizeLabel: String get() = formatBytes(bytes)
}

/**
 * Downloads and records offline imagery areas.
 *
 * Tiles come straight from LINZ into [OfflineTileStore] - the same store the
 * map's own tile server reads - so a downloaded area is usable the moment it
 * lands, with no export or import step. This replaces MapLibre's OfflineManager
 * for imagery, which could only be pointed at LINZ's hosted style and measured
 * 3,175 resources / 74.6 MB for an area needing ~304 aerial tiles (~13 MB),
 * because that style declares two terrain sources its own layers never use and
 * MapLibre walks every source in a style.
 *
 * The Room row is the *record* for the screen. The tiles are shared between
 * areas and with ordinary map browsing, so deleting a record frees no disk
 * space - which is why [clearTiles] exists as a separate, explicit action.
 */
class OfflineAreaManager(
    private val store: OfflineTileStore,
    private val dao: OfflineAreaDao,
    /** Injected so tests never reach LINZ. */
    private val fetcherFor: (apiKey: String) -> TileFetcher = { key -> LinzTileFetcher(key) },
    private val now: () -> Long = System::currentTimeMillis
) {

    /** The screen's list, straight from the database, updating as progress lands. */
    fun observeAreas(): Flow<List<OfflineArea>> =
        dao.observeAll().map { rows -> rows.map { it.toOfflineArea() } }

    suspend fun listAreas(): List<OfflineArea> = dao.getAll().map { it.toOfflineArea() }

    suspend fun area(id: Long): OfflineArea? = dao.getById(id)?.toOfflineArea()

    /** Tiles and bytes actually on disk, covering every area and map browsing. */
    suspend fun summary(): TileStoreSummary =
        TileStoreSummary(store.storedTileCount(), store.storedBytes())

    /**
     * Validates the plan and records it, so progress has somewhere to be written
     * before the first tile arrives. Throws [IllegalArgumentException] for a plan
     * too large to be a sensible offline pack.
     */
    suspend fun createArea(plan: OfflineAreaPlan): OfflineArea {
        val planned = plan.tileCount
        require(planned <= MAX_TILES_PER_AREA) {
            "That area needs $planned tiles, more than the $MAX_TILES_PER_AREA limit. " +
                "Narrow the zoom range or the area."
        }
        val id = dao.insert(
            OfflineAreaEntity(
                name = plan.name,
                minLat = plan.bounds.minLat,
                minLng = plan.bounds.minLng,
                maxLat = plan.bounds.maxLat,
                maxLng = plan.bounds.maxLng,
                minZoom = plan.minZoom,
                maxZoom = plan.maxZoom,
                plannedTiles = planned,
                createdAtEpochMs = now()
            )
        )
        return area(id) ?: error("area $id was not stored")
    }

    /**
     * Downloads a recorded area, resuming from the tiles already on disk.
     *
     * Cancelling is safe and cheap to resume: tiles land on disk as they arrive
     * and progress is written to the database as it goes, so a second call picks
     * up where this one stopped rather than starting over.
     */
    suspend fun download(areaId: Long, apiKey: String): OfflineArea {
        val row = dao.getById(areaId) ?: error("no offline area with id $areaId")
        val downloader = TileDownloader(store = store, fetcher = fetcherFor(apiKey))

        var lastWrite = 0L
        val progress = downloader.download(
            bounds = LatLngBounds(row.minLat, row.minLng, row.maxLat, row.maxLng),
            minZoom = row.minZoom,
            maxZoom = row.maxZoom
        ) { current ->
            val at = now()
            // The screen reads the database, so progress is written as it happens
            // - but not once per chunk of four tiles.
            if (at - lastWrite >= PROGRESS_WRITE_MS) {
                lastWrite = at
                dao.updateProgress(areaId, current.stored.toLong(), current.bytes)
            }
        }

        if (progress.failed == 0) {
            // Tiles LINZ serves no imagery for - the edge of coverage, offshore -
            // will never arrive. The area is as complete as it can be: mark it
            // finished and let missingTiles report the shortfall.
            dao.markComplete(
                id = areaId,
                completedAtEpochMs = now(),
                downloaded = progress.stored.toLong(),
                bytes = progress.bytes
            )
        } else {
            dao.updateProgress(areaId, progress.stored.toLong(), progress.bytes)
            dao.recordError(
                areaId,
                "${progress.failed} of ${progress.total} tiles failed to download."
            )
        }
        return area(areaId) ?: error("area $areaId vanished during download")
    }

    /** Forgets an area. The tiles stay: they are shared, and this frees no space. */
    suspend fun deleteArea(areaId: Long) {
        dao.delete(areaId)
    }

    /**
     * Removes every downloaded tile and every area record, returning how many
     * files went. The only action that actually reclaims disk space.
     */
    suspend fun clearTiles(): Int {
        val removed = store.deleteAll()
        dao.deleteAll()
        return removed
    }

    companion object {
        /**
         * ~540 MB at the 45 KB average tile size our estimates assume. High
         * enough for a large block across zoom 10-16, low enough that a
         * mis-typed zoom range cannot fill the device.
         */
        const val MAX_TILES_PER_AREA = 12_000L

        private const val PROGRESS_WRITE_MS = 1_000L
    }
}

private fun OfflineAreaEntity.toOfflineArea() = OfflineArea(
    id = id,
    name = name,
    plannedTiles = plannedTiles.toInt(),
    storedTiles = downloadedTiles.toInt(),
    bytes = bytes,
    isComplete = isComplete,
    lastError = lastError
)
