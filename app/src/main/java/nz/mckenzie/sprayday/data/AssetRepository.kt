package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.data.db.AssetPointEntity
import nz.mckenzie.sprayday.data.db.AssetProductDefaultEntity
import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.gpx.GpxParser
import nz.mckenzie.sprayday.domain.gpx.GpxWriter
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import java.time.ZoneId

/**
 * Planned tracks, the product catalogue and spray records.
 *
 * Multi-table writes run inside [SprayDayDatabase.withTransaction] so a spray
 * can never be half-recorded.
 */
class AssetRepository(
    private val db: SprayDayDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val assetDao = db.assetDao()
    private val sprayEventDao = db.sprayEventDao()
    private val productDao = db.productDao()

    // --- Tracks ---------------------------------------------------------------------

    /**
     * Active tracks with their due status, recalculated whenever the data or the
     * clock tick changes. Pass [MinuteTicker.minutes] from the UI so the colours
     * roll over while the app stays open.
     */
    fun observeAssetsWithDue(
        nowProvider: Flow<Long> = MinuteTicker.minutes()
    ): Flow<List<AssetWithDue>> = combine(
        assetDao.observeActiveAssets(),
        sprayEventDao.observeSpraySummaries(),
        nowProvider
    ) { tracks, summaries, now ->
        val byTrack = summaries.associateBy { it.assetId }
        tracks.map { track ->
            val summary = byTrack[track.id]
            // The event table wins over the denormalised column if they disagree.
            val lastSprayedAt = summary?.lastSprayedAtEpochMs ?: track.lastSprayedAtEpochMs
            AssetWithDue(
                track = track,
                due = DueCalculator.calculate(
                    lastSprayedAtEpochMs = lastSprayedAt,
                    intervalDays = track.intervalDays,
                    leadDays = AssetEntity.DEFAULT_LEAD_DAYS,
                    nowEpochMs = now,
                    zoneId = zoneId
                ),
                sprayCount = summary?.sprayCount ?: 0
            )
        }
    }

    fun observeAsset(assetId: Long): Flow<AssetEntity?> = assetDao.observeAsset(assetId)

    fun observeAssetGeometry(assetId: Long): Flow<List<GeoPoint>> =
        assetDao.observeGeometry(assetId).map { rows -> rows.map { it.toGeoPoint() } }

    suspend fun getAsset(assetId: Long): AssetEntity? = assetDao.getAsset(assetId)

    suspend fun getAssetGeometry(assetId: Long): List<GeoPoint> =
        assetDao.getGeometry(assetId).map { it.toGeoPoint() }

    /**
     * The box containing every planned track, if there is any geometry yet. Used to
     * centre things on the operator's own work rather than a guessed location.
     */
    suspend fun assetBounds(): LatLngBounds? {
        val bounds = assetDao.pointBounds() ?: return null
        val minLat = bounds.minLat ?: return null
        val minLng = bounds.minLng ?: return null
        val maxLat = bounds.maxLat ?: return null
        val maxLng = bounds.maxLng ?: return null
        return LatLngBounds(minLat = minLat, minLng = minLng, maxLat = maxLat, maxLng = maxLng)
    }

    /** Creates a track, stores its planned geometry and computes its length. */
    suspend fun createAsset(
        name: String,
        geometry: List<GeoPoint>,
        areaLabel: String? = null,
        notes: String? = null,
        intervalDays: Int = AssetEntity.DEFAULT_INTERVAL_DAYS,
        swathWidthM: Double? = null,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        val assetId = assetDao.insert(
            AssetEntity(
                name = name,
                areaLabel = areaLabel,
                notes = notes,
                intervalDays = intervalDays,
                swathWidthM = swathWidthM,
                createdAtEpochMs = createdAtEpochMs
            )
        )
        storeGeometry(assetId, geometry)
        assetId
    }

    suspend fun updateAsset(track: AssetEntity) = assetDao.update(track)

    suspend fun replaceGeometry(assetId: Long, geometry: List<GeoPoint>) = db.withTransaction {
        storeGeometry(assetId, geometry)
    }

    suspend fun deleteAsset(assetId: Long) = assetDao.delete(assetId)

    private suspend fun storeGeometry(assetId: Long, geometry: List<GeoPoint>) {
        val rows = geometry.mapIndexed { index, point ->
            AssetPointEntity(assetId = assetId, sequence = index, lat = point.lat, lng = point.lng)
        }
        assetDao.replaceGeometry(assetId, rows, polylineLengthMeters(geometry))
    }

    // --- GPX interchange ------------------------------------------------------------

    /** Exports a track's planned geometry as GPX 1.1, or null if the track is gone. */
    suspend fun exportAssetGpx(assetId: Long): String? {
        val track = assetDao.getAsset(assetId) ?: return null
        return GpxWriter.write(track.name, getAssetGeometry(assetId))
    }

    /** Imports a GPX file as a new track. Throws if it has fewer than two points. */
    suspend fun importAssetGpx(
        name: String,
        gpx: String,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val geometry = GpxParser.parse(gpx)
        require(geometry.size >= 2) { "A track needs at least two points" }
        return createAsset(name = name, geometry = geometry, createdAtEpochMs = createdAtEpochMs)
    }
}

private fun AssetPointEntity.toGeoPoint() = GeoPoint(lat = lat, lng = lng)
