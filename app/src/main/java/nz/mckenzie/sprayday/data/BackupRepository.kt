package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.RecordedPointEntity
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.data.db.TrackEntity
import nz.mckenzie.sprayday.data.db.TrackPointEntity
import nz.mckenzie.sprayday.data.db.TrackProductDefaultEntity
import nz.mckenzie.sprayday.domain.backup.BackupDocument
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.domain.backup.LinePointRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.backup.RecordedPointRecord
import nz.mckenzie.sprayday.domain.backup.RecordingRecord
import nz.mckenzie.sprayday.domain.backup.SprayEventRecord
import nz.mckenzie.sprayday.domain.backup.SprayProductRecord
import nz.mckenzie.sprayday.domain.backup.TrackDefaultRecord
import nz.mckenzie.sprayday.domain.backup.TrackRecord

/**
 * Turning the database into a backup file, and back again.
 *
 * The ids are carried through both ways because they are what holds a season
 * together: a track, its geometry, the sprays on it and the recordings that prove
 * them are linked by id, and a restore that renumbered them would leave a database
 * that looks right and connects nothing.
 *
 * Restore replaces. It clears the backup's tables and inserts the file's contents
 * inside one transaction, so an interrupted restore leaves the previous data intact
 * rather than a mixture of two seasons. Downloaded offline areas are left alone:
 * they describe tiles on this device, which the file has nothing to say about.
 */
class BackupRepository(
    private val db: SprayDayDatabase,
    private val appVersion: String,
    /** Recorded points keep their fix times but not their row ids. */
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val dao = db.backupDao()

    /** Everything the app holds, ready to be written to a file. */
    suspend fun export(): BackupDocument {
        val pointsByTrack = dao.allTrackPoints().groupBy { it.trackId }
        val productsByEvent = dao.allSprayEventProducts().groupBy { it.sprayEventId }
        val pointsBySession = dao.allRecordedPoints().groupBy { it.sessionId }

        return BackupDocument(
            exportedAtEpochMs = nowEpochMs(),
            appVersion = appVersion,
            products = dao.allProducts().map { it.toRecord() },
            tracks = dao.allTracks().map { track -> track.toRecord(pointsByTrack[track.id].orEmpty()) },
            sprayEvents = dao.allSprayEvents().map { event -> event.toRecord(productsByEvent[event.id].orEmpty()) },
            trackDefaults = dao.allTrackDefaults().map { it.toRecord() },
            recordings = dao.allRecordedSessions().map { session ->
                session.toRecord(pointsBySession[session.id].orEmpty())
            }
        )
    }

    /** What is in the app right now, for showing beside what a file holds. */
    suspend fun currentSummary(): BackupSummary = BackupSummary(
        tracks = dao.trackCount(),
        sprays = dao.sprayCount(),
        recordings = dao.recordingCount(),
        products = dao.productCount(),
        points = dao.pointCount()
    )

    /** Replaces everything the app holds with the contents of [document]. */
    suspend fun restore(document: BackupDocument): BackupSummary = db.withTransaction {
        require(document.format == BackupDocument.FORMAT) {
            "That file is not a Spray Day backup."
        }
        require(document.version <= BackupDocument.VERSION) {
            "That backup was written by a newer version of Spray Day."
        }

        // Children before parents, so no foreign key is ever left pointing at nothing.
        dao.clearTrackDefaults()
        dao.clearSprayEventProducts()
        dao.clearSprayEvents()
        dao.clearTrackPoints()
        dao.clearTracks()
        dao.clearProducts()
        dao.clearRecordedPoints()
        dao.clearRecordedSessions()

        // And parents before children on the way back in.
        dao.insertProducts(document.products.map { it.toEntity() })
        dao.insertTracks(document.tracks.map { it.toEntity() })
        dao.insertTrackPoints(
            document.tracks.flatMap { track ->
                track.points.mapIndexed { index, point ->
                    TrackPointEntity(trackId = track.id, sequence = index, lat = point.lat, lng = point.lng)
                }
            }
        )
        dao.insertSprayEvents(document.sprayEvents.map { it.toEntity() })
        dao.insertSprayEventProducts(
            document.sprayEvents.flatMap { event ->
                event.products.map { product ->
                    SprayEventProductEntity(
                        sprayEventId = event.id,
                        productId = product.productId,
                        quantityMl = product.quantityMl
                    )
                }
            }
        )
        dao.insertTrackDefaults(document.trackDefaults.map { it.toEntity() })
        dao.insertRecordedSessions(document.recordings.map { it.toEntity() })
        dao.insertRecordedPoints(
            document.recordings.flatMap { session ->
                session.points.map { point ->
                    RecordedPointEntity(
                        sessionId = session.id,
                        sequence = point.sequence,
                        lat = point.lat,
                        lng = point.lng,
                        altitudeM = point.altitudeM,
                        accuracyM = point.accuracyM,
                        speedMps = point.speedMps,
                        bearingDeg = point.bearingDeg,
                        recordedAtEpochMs = point.recordedAtEpochMs
                    )
                }
            }
        )

        BackupFormat.summarise(document)
    }
}

private fun TrackEntity.toRecord(points: List<TrackPointEntity>) = TrackRecord(
    id = id,
    name = name,
    areaLabel = areaLabel,
    notes = notes,
    intervalDays = intervalDays,
    swathWidthM = swathWidthM,
    active = active,
    createdAtEpochMs = createdAtEpochMs,
    lastSprayedAtEpochMs = lastSprayedAtEpochMs,
    lengthM = lengthM,
    points = points.sortedBy { it.sequence }.map { LinePointRecord(lat = it.lat, lng = it.lng) }
)

private fun TrackRecord.toEntity() = TrackEntity(
    id = id,
    name = name,
    areaLabel = areaLabel,
    notes = notes,
    intervalDays = intervalDays,
    swathWidthM = swathWidthM,
    active = active,
    createdAtEpochMs = createdAtEpochMs,
    lastSprayedAtEpochMs = lastSprayedAtEpochMs,
    lengthM = lengthM
)

private fun ProductEntity.toRecord() = ProductRecord(
    id = id,
    name = name,
    unit = unit,
    rateText = rateText,
    notes = notes,
    archived = archived
)

private fun ProductRecord.toEntity() = ProductEntity(
    id = id,
    name = name,
    unit = unit,
    rateText = rateText,
    notes = notes,
    archived = archived
)

private fun SprayEventEntity.toRecord(products: List<SprayEventProductEntity>) = SprayEventRecord(
    id = id,
    trackId = trackId,
    sprayedAtEpochMs = sprayedAtEpochMs,
    waterLitres = waterLitres,
    operatorName = operatorName,
    notes = notes,
    distanceM = distanceM,
    areaSqm = areaSqm,
    recordedSessionId = recordedSessionId,
    products = products.map { SprayProductRecord(productId = it.productId, quantityMl = it.quantityMl) }
)

private fun SprayEventRecord.toEntity() = SprayEventEntity(
    id = id,
    trackId = trackId,
    sprayedAtEpochMs = sprayedAtEpochMs,
    waterLitres = waterLitres,
    operatorName = operatorName,
    notes = notes,
    distanceM = distanceM,
    areaSqm = areaSqm,
    recordedSessionId = recordedSessionId
)

private fun TrackProductDefaultEntity.toRecord() = TrackDefaultRecord(
    trackId = trackId,
    productId = productId,
    defaultQuantityMl = defaultQuantityMl
)

private fun TrackDefaultRecord.toEntity() = TrackProductDefaultEntity(
    trackId = trackId,
    productId = productId,
    defaultQuantityMl = defaultQuantityMl
)

private fun RecordedSessionEntity.toRecord(points: List<RecordedPointEntity>) = RecordingRecord(
    id = id,
    name = name,
    trackId = trackId,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    status = status,
    distanceM = distanceM,
    durationMs = durationMs,
    pointCount = pointCount,
    points = points.sortedBy { it.sequence }.map {
        RecordedPointRecord(
            sequence = it.sequence,
            lat = it.lat,
            lng = it.lng,
            altitudeM = it.altitudeM,
            accuracyM = it.accuracyM,
            speedMps = it.speedMps,
            bearingDeg = it.bearingDeg,
            recordedAtEpochMs = it.recordedAtEpochMs
        )
    }
)

private fun RecordingRecord.toEntity() = RecordedSessionEntity(
    id = id,
    name = name,
    trackId = trackId,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    status = status,
    distanceM = distanceM,
    durationMs = durationMs,
    pointCount = pointCount
)
