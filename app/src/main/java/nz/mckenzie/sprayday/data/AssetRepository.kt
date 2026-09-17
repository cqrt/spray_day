package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.data.db.AssetPointEntity
import nz.mckenzie.sprayday.data.db.AssetProductDefaultEntity
import nz.mckenzie.sprayday.data.db.GroupEntity
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.gpx.GpxParser
import nz.mckenzie.sprayday.domain.gpx.GpxWriter
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import java.time.ZoneId

/**
 * The asset list, the product catalogue and spray records.
 *
 * "Asset" is the wider word on purpose: the list holds tracks, roads and pieces of
 * infrastructure, and they differ only in their kind, shape and spray method.
 *
 * Multi-table writes run inside [SprayDayDatabase.withTransaction] so a spray
 * can never be half-recorded, and so an asset and the group it belongs to cannot
 * disagree about who is in what.
 */
class AssetRepository(
    private val db: SprayDayDatabase,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val assetDao = db.assetDao()
    private val groupDao = db.groupDao()
    private val sprayEventDao = db.sprayEventDao()
    private val productDao = db.productDao()

    // --- Assets ----------------------------------------------------------------------

    /**
     * Active assets with their due status, recalculated whenever the data or the
     * clock tick changes. Pass [MinuteTicker.minutes] from the UI so the colours
     * roll over while the app stays open.
     */
    fun observeAssetsWithDue(
        nowProvider: Flow<Long> = MinuteTicker.minutes()
    ): Flow<List<AssetWithDue>> = combine(
        assetDao.observeActiveAssets(),
        sprayEventDao.observeSpraySummaries(),
        assetDao.observeAssetGroupNames(),
        nowProvider
    ) { assets, summaries, groupNames, now ->
        val byAsset = summaries.associateBy { it.assetId }
        val blocks = groupNames.associate { it.assetId to it.name }
        assets.map { asset ->
            val summary = byAsset[asset.id]
            // The event table wins over the denormalised column if they disagree.
            val lastSprayedAt = summary?.lastSprayedAtEpochMs ?: asset.lastSprayedAtEpochMs
            AssetWithDue(
                asset = asset,
                due = DueCalculator.calculate(
                    lastSprayedAtEpochMs = lastSprayedAt,
                    intervalDays = asset.intervalDays,
                    leadDays = AssetEntity.DEFAULT_LEAD_DAYS,
                    nowEpochMs = now,
                    zoneId = zoneId
                ),
                sprayCount = summary?.sprayCount ?: 0,
                // The name rather than the id, because the name is what the list shows and
                // what a rename would change without touching a single asset row.
                groupName = blocks[asset.id]
            )
        }
    }

    fun observeAsset(assetId: Long): Flow<AssetEntity?> = assetDao.observeAsset(assetId)

    fun observeAssetGeometry(assetId: Long): Flow<List<GeoPoint>> =
        assetDao.observeGeometry(assetId).map { rows -> rows.map { it.toGeoPoint() } }

    /** The name of the group an asset belongs to, or null when it stands alone. */
    fun observeGroupName(assetId: Long): Flow<String?> = assetDao.observeGroupName(assetId)

    suspend fun getAsset(assetId: Long): AssetEntity? = assetDao.getAsset(assetId)

    suspend fun getAssetGeometry(assetId: Long): List<GeoPoint> =
        assetDao.getGeometry(assetId).map { it.toGeoPoint() }

    /**
     * The box containing every planned asset, if there is any geometry yet. Used to
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

    /**
     * Creates an asset, stores its planned geometry and computes its length.
     *
     * The group is given by name rather than id because that is what the operator
     * types or reads off a backup; an unknown name becomes a new group, which is what
     * "put this in the Estuary block" means when the block is new.
     */
    suspend fun createAsset(
        name: String,
        geometry: List<GeoPoint>,
        kind: AssetKind = AssetKind.TRACK,
        shape: AssetShape = AssetShape.LINE,
        method: SprayMethod = SprayMethod.UNSET,
        groupName: String? = null,
        notes: String? = null,
        intervalDays: Int = AssetEntity.DEFAULT_INTERVAL_DAYS,
        swathWidthM: Double? = null,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long = db.withTransaction {
        val assetId = assetDao.insert(
            AssetEntity(
                name = name,
                kind = kind.name,
                shape = shape.name,
                method = method.name,
                groupId = groupIdFor(groupName),
                notes = notes,
                intervalDays = intervalDays,
                swathWidthM = swathWidthM,
                createdAtEpochMs = createdAtEpochMs
            )
        )
        storeGeometry(assetId, geometry)
        assetId
    }

    /**
     * Writes an edited asset back, resolving the group name the form collected.
     *
     * Kept apart from [updateAsset] on purpose: this one is allowed to move an asset
     * between groups (including out of one, when the name is blank), while the plain
     * update must leave the grouping exactly as it found it.
     */
    suspend fun saveAssetEdits(asset: AssetEntity, groupName: String?) = db.withTransaction {
        assetDao.update(asset.copy(groupId = groupIdFor(groupName)))
    }

    suspend fun updateAsset(asset: AssetEntity) = assetDao.update(asset)

    suspend fun replaceGeometry(assetId: Long, geometry: List<GeoPoint>) = db.withTransaction {
        storeGeometry(assetId, geometry)
    }

    suspend fun deleteAsset(assetId: Long) = assetDao.delete(assetId)

    /**
     * The id a typed group name means, creating the group when the name is new.
     *
     * Null for a blank name, which is how the operator takes an asset back out of a
     * group. Two operators typing "Estuary" and "estuary" get the same group, because
     * the name column is case-insensitive.
     */
    private suspend fun groupIdFor(name: String?): Long? {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return null
        groupDao.findByName(clean)?.let { return it.id }
        val inserted = groupDao.insertIfAbsent(GroupEntity(name = clean))
        // -1 means the name appeared between the lookup and the insert.
        return if (inserted == -1L) groupDao.findByName(clean)?.id else inserted
    }

    private suspend fun storeGeometry(assetId: Long, geometry: List<GeoPoint>) {
        val rows = geometry.mapIndexed { index, point ->
            AssetPointEntity(assetId = assetId, sequence = index, lat = point.lat, lng = point.lng)
        }
        assetDao.replaceGeometry(assetId, rows, polylineLengthMeters(geometry))
    }

    // --- GPX interchange ------------------------------------------------------------

    /** Exports an asset's planned geometry as GPX 1.1, or null if the asset is gone. */
    suspend fun exportAssetGpx(assetId: Long): String? {
        val asset = assetDao.getAsset(assetId) ?: return null
        return GpxWriter.write(asset.name, getAssetGeometry(assetId))
    }

    /** Imports a GPX file as a new track. Throws if it has fewer than two points. */
    suspend fun importAssetGpx(
        name: String,
        gpx: String,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long {
        val geometry = GpxParser.parse(gpx)
        require(geometry.size >= 2) { "A line needs at least two points" }
        return createAsset(name = name, geometry = geometry, createdAtEpochMs = createdAtEpochMs)
    }
}

private fun AssetPointEntity.toGeoPoint() = GeoPoint(lat = lat, lng = lng)
