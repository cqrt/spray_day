package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.data.db.AssetPointEntity
import nz.mckenzie.sprayday.data.db.AssetProductDefaultEntity
import nz.mckenzie.sprayday.data.db.GroupEntity
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.RecordedPointEntity
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.domain.backup.AssetDefaultRecord
import nz.mckenzie.sprayday.domain.backup.AssetRecord
import nz.mckenzie.sprayday.domain.backup.BackupDocument
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.BackupSettingsRecord
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.LinePointRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.backup.RecordedPointRecord
import nz.mckenzie.sprayday.domain.backup.RecordingRecord
import nz.mckenzie.sprayday.domain.backup.SprayEventRecord
import nz.mckenzie.sprayday.domain.backup.SprayProductRecord

/**
 * Turning the database into a backup file, and back again.
 *
 * The ids are carried through both ways because they are what holds a season
 * together: an asset, its geometry, the sprays on it and the recordings that prove
 * them are linked by id, and a restore that renumbered them would leave a database
 * that looks right and connects nothing. Groups are carried by id for the same reason.
 *
 * Restore replaces. It clears the backup's tables and inserts the file's contents
 * inside one transaction, so an interrupted restore leaves the previous data intact
 * rather than a mixture of two seasons. Downloaded offline areas are left alone:
 * they describe tiles on this device, which the file has nothing to say about.
 *
 * Files written before groups existed (format 1) still restore: their per-asset
 * "block or area" becomes a group of that name, so the operator's Estuary block comes
 * back as one.
 */
class BackupRepository(
    private val db: SprayDayDatabase,
    private val appVersion: String,
    /** Recorded points keep their fix times but not their row ids. */
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val dao = db.backupDao()

    /** Everything the app holds, ready to be written to a file. */
    suspend fun export(settings: BackupSettingsRecord? = null): BackupDocument {
        val pointsByAsset = dao.allAssetPoints().groupBy { it.assetId }
        val productsByEvent = dao.allSprayEventProducts().groupBy { it.sprayEventId }
        val pointsBySession = dao.allRecordedPoints().groupBy { it.sessionId }

        return BackupDocument(
            exportedAtEpochMs = nowEpochMs(),
            appVersion = appVersion,
            products = dao.allProducts().map { it.toRecord() },
            groups = dao.allGroups().map { it.toRecord() },
            assets = dao.allAssets().map { asset -> asset.toRecord(pointsByAsset[asset.id].orEmpty()) },
            sprayEvents = dao.allSprayEvents().map { event -> event.toRecord(productsByEvent[event.id].orEmpty()) },
            assetDefaults = dao.allAssetDefaults().map { it.toRecord() },
            recordings = dao.allRecordedSessions().map { session ->
                session.toRecord(pointsBySession[session.id].orEmpty())
            },
            settings = settings
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
        dao.clearGroups()
        dao.clearProducts()
        dao.clearRecordedPoints()
        dao.clearRecordedSessions()

        // And parents before children on the way back in.
        val groupIds = restoreGroups(document)
        dao.insertProducts(document.products.map { it.toEntity() })
        dao.insertAssets(
            document.assets.map { asset ->
                asset.toEntity(groupId = asset.groupId ?: groupIds[asset.areaLabel.groupKey()])
            }
        )
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

    /**
     * Writes the file's groups and returns the id each name means.
     *
     * A file written before groups existed has no group rows at all, only a "block or
     * area" on each asset. Those names become groups here, deduplicated the way the
     * name column itself compares them, so a season that was labelled "Estuary",
     * "estuary" and " Estuary " comes back as one group rather than three.
     */
    private suspend fun restoreGroups(document: BackupDocument): Map<String, Long> {
        if (document.groups.isNotEmpty()) {
            dao.insertGroups(
                document.groups.map { GroupEntity(id = it.id, name = it.name, notes = it.notes) }
            )
            return document.groups.associate { it.name.groupKey() to it.id }
        }

        val groupDao = db.groupDao()
        val ids = mutableMapOf<String, Long>()
        document.assets
            .mapNotNull { it.areaLabel?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .distinctBy { it.groupKey() }
            .forEach { name ->
                val existing = groupDao.findByName(name)?.id
                val inserted = existing ?: groupDao.insertIfAbsent(GroupEntity(name = name))
                val id = if (inserted == -1L) groupDao.findByName(name)?.id else inserted
                if (id != null) ids[name.groupKey()] = id
            }
        return ids
    }
}

/**
 * The key two group names are the same under. Lowercase because the name column is
 * NOCASE, trimmed because a name typed with a stray space is the same name.
 */
private fun String?.groupKey(): String = this?.trim().orEmpty().lowercase()

private fun AssetEntity.toRecord(points: List<AssetPointEntity>) = AssetRecord(
    id = id,
    name = name,
    groupId = groupId,
    kind = kind,
    shape = shape,
    method = method,
    notes = notes,
    intervalDays = intervalDays,
    swathWidthM = swathWidthM,
    active = active,
    createdAtEpochMs = createdAtEpochMs,
    lastSprayedAtEpochMs = lastSprayedAtEpochMs,
    lengthM = lengthM,
    points = points.sortedBy { it.sequence }.map { LinePointRecord(lat = it.lat, lng = it.lng) }
)

private fun GroupEntity.toRecord() = GroupRecord(
    id = id,
    name = name,
    notes = notes
)

private fun AssetRecord.toEntity(groupId: Long?) = AssetEntity(
    id = id,
    name = name,
    kind = kind,
    shape = shape,
    method = method,
    groupId = groupId,
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
