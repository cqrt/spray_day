package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.data.db.TrackEntity
import nz.mckenzie.sprayday.data.db.TrackPointEntity
import nz.mckenzie.sprayday.data.db.TrackProductDefaultEntity
import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.gpx.GpxParser
import nz.mckenzie.sprayday.domain.gpx.GpxWriter
import java.time.ZoneId

/**
 * Planned tracks, the product catalogue and spray records.
 *
 * Multi-table writes run inside [SprayDayDatabase.withTransaction] so a spray
 * can never be half-recorded.
 */
class TrackRepository(
    private val db: SprayDayDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val trackDao = db.trackDao()
    private val sprayEventDao = db.sprayEventDao()
    private val productDao = db.productDao()

    // --- Tracks ---------------------------------------------------------------------

    /**
     * Active tracks with their due status, recalculated whenever the data or the
     * clock tick changes. Pass [TrackTicker.minutes] from the UI so the colours
     * roll over while the app stays open.
     */
    fun observeTracksWithDue(
        nowProvider: Flow<Long> = TrackTicker.minutes()
    ): Flow<List<TrackWithDue>> = combine(
        trackDao.observeActiveTracks(),
        sprayEventDao.observeSpraySummaries(),
        nowProvider
    ) { tracks, summaries, now ->
        val byTrack = summaries.associateBy { it.trackId }
        tracks.map { track ->
            val summary = byTrack[track.id]
            // The event table wins over the denormalised column if they disagree.
            val lastSprayedAt = summary?.lastSprayedAtEpochMs ?: track.lastSprayedAtEpochMs
            TrackWithDue(
                track = track,
                due = DueCalculator.calculate(
                    lastSprayedAtEpochMs = lastSprayedAt,
                    intervalDays = track.intervalDays,
                    leadDays = TrackEntity.DEFAULT_LEAD_DAYS,
                    nowEpochMs = now,
                    zoneId = zoneId
                ),
                sprayCount = summary?.sprayCount ?: 0
            )
        }
    }

    fun observeTrack(trackId: Long): Flow<TrackEntity?> = trackDao.observeTrack(trackId)

    fun observeTrackGeometry(trackId: Long): Flow<List<GeoPoint>> =
        trackDao.observeGeometry(trackId).map { rows -> rows.map { it.toGeoPoint() } }

    suspend fun getTrack(trackId: Long): TrackEntity? = trackDao.getTrack(trackId)

    suspend fun getTrackGeometry(trackId: Long): List<GeoPoint> =
        trackDao.getGeometry(trackId).map { it.toGeoPoint() }

    /** Creates a track, stores its planned geometry and computes its length. */
    suspend fun createTrack(
        name: String,
        geometry: List<GeoPoint>,
        areaLabel: String? = null,
        notes: String? = null,
        intervalDays: Int = TrackEntity.DEFAULT_INTERVAL_DAYS,
        swathWidthM: Double? = null,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        val trackId = trackDao.insert(
            TrackEntity(
                name = name,
                areaLabel = areaLabel,
                notes = notes,
                intervalDays = intervalDays,
                swathWidthM = swathWidthM,
                createdAtEpochMs = createdAtEpochMs
            )
        )
        storeGeometry(trackId, geometry)
        trackId
    }

    suspend fun updateTrack(track: TrackEntity) = trackDao.update(track)

    suspend fun replaceGeometry(trackId: Long, geometry: List<GeoPoint>) = db.withTransaction {
        storeGeometry(trackId, geometry)
    }

    suspend fun deleteTrack(trackId: Long) = trackDao.delete(trackId)

    private suspend fun storeGeometry(trackId: Long, geometry: List<GeoPoint>) {
        val rows = geometry.mapIndexed { index, point ->
            TrackPointEntity(trackId = trackId, sequence = index, lat = point.lat, lng = point.lng)
        }
        trackDao.replaceGeometry(trackId, rows, polylineLengthMeters(geometry))
    }

    // --- GPX interchange ------------------------------------------------------------

    /** Exports a track's planned geometry as GPX 1.1, or null if the track is gone. */
    suspend fun exportTrackGpx(trackId: Long): String? {
        val track = trackDao.getTrack(trackId) ?: return null
        return GpxWriter.write(track.name, getTrackGeometry(trackId))
    }

    /** Imports a GPX file as a new track. Throws if it has fewer than two points. */
    suspend fun importTrackGpx(
        name: String,
        gpx: String,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val geometry = GpxParser.parse(gpx)
        require(geometry.size >= 2) { "A track needs at least two points" }
        return createTrack(name = name, geometry = geometry, createdAtEpochMs = createdAtEpochMs)
    }
}

private fun TrackPointEntity.toGeoPoint() = GeoPoint(lat = lat, lng = lng)
