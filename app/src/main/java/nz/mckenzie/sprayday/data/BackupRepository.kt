package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.RecordedPointEntity
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.data.db.AssetPointEntity
import nz.mckenzie.sprayday.data.db.AssetProductDefaultEntity
import nz.mckenzie.sprayday.domain.backup.BackupDocument
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.domain.backup.LinePointRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.backup.RecordedPointRecord
import nz.mckenzie.sprayday.domain.backup.RecordingRecord
import nz.mckenzie.sprayday.domain.backup.SprayEventRecord
import nz.mckenzie.sprayday.domain.backup.SprayProductRecord
import nz.mckenzie.sprayday.domain.backup.AssetDefaultRecord
import nz.mckenzie.sprayday.domain.backup.AssetRecord

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
        val pointsByAsset = dao.allAssetPoints().groupBy { it.assetId }
        val productsByEvent = dao.allSprayEventProducts().groupBy { it.sprayEventId }
        val pointsBySession = dao.allRecordedPoints().groupBy { it.sessionId }

        return BackupDocument(
            exportedAtEpochMs = nowEpochMs(),
            appVersion = appVersion,
            products = dao.allProducts().map { it.toRecord() },
            assets = dao.allAssets().map { asset -> asset.toRecord(pointsByAsset[asset.id].orEmpty()) },
            sprayEvents = dao.allSprayEvents().map { event -> event.toRecord(productsByEvent[event.id].orEmpty()) },
            assetDefaults = dao.allAssetDefaults().map { it.toRecord() },
            recordings = dao.allRecordedSessions().map { session ->
                session.toRecord(pointsBySession[session.id].orEmpty())
            }
        )
    }

    /** What is in the app right now, for showing beside what a file holds. */
    suspend fun currentSummary(): BackupSummary = BackupSummary(
        tracks = dao.assetCount(),
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
        dao.clearAssetDefaults()
        dao.clearSprayEventProducts()
        dao.clearSprayEvents()
        dao.clearAssetPoints()
        dao.clearAssets()
        dao.clearProducts()
        dao.clearRecordedPoints()
        dao.clearRecordedSessions()

        // And parents before children on the way back in.
        dao.insertProducts(document.products.map { it.toEntity() })
        dao.insertAssets(document.assets.map { it.toEntity() })
        dao.insertAssetPoints(
            document.assets.flatMap { asset ->
                asset.points.mapIndexed { index, point ->
                    AssetPointEntity(assetId = asset.id, sequence = index, lat = point.lat, lng = point.lng)
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
        dao.insertAssetDefaults(document.assetDefaults.map { it.toEntity() })
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

private fun AssetEntity.toRecord(points: List<AssetPointEntity>) = AssetRecord(
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

private fun AssetRecord.toEntity() = AssetEntity(
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
    assetId = assetId,
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
    assetId = assetId,
    sprayedAtEpochMs = sprayedAtEpochMs,
    waterLitres = waterLitres,
    operatorName = operatorName,
    notes = notes,
    distanceM = distanceM,
    areaSqm = areaSqm,
    recordedSessionId = recordedSessionId
)

private fun AssetProductDefaultEntity.toRecord() = AssetDefaultRecord(
    assetId = assetId,
    productId = productId,
    defaultQuantityMl = defaultQuantityMl
)

private fun AssetDefaultRecord.toEntity() = AssetProductDefaultEntity(
    assetId = assetId,
    productId = productId,
    defaultQuantityMl = defaultQuantityMl
)

private fun RecordedSessionEntity.toRecord(points: List<RecordedPointEntity>) = RecordingRecord(
    id = id,
    name = name,
    assetId = assetId,
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
    assetId = assetId,
    startedAtEpochMs = startedAtEpochMs,
    endedAtEpochMs = endedAtEpochMs,
    status = status,
    distanceM = distanceM,
    durationMs = durationMs,
    pointCount = pointCount
)
