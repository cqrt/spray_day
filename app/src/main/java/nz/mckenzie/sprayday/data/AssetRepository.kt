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
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.RecordedPass
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
    private val recordingDao = db.recordingDao()

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

    /**
     * An asset's geometry as it is stored: the line, and the side tracks hanging off it.
     *
     * A [Flow] of the whole thing rather than of the line, because the map draws every path: a track
     * with a spur into the gully is two lines on the screen, drawn in the one colour the asset's
     * traffic light gives them.
     */
    fun observeAssetGeometry(assetId: Long): Flow<AssetGeometry> =
        assetDao.observeGeometry(assetId).map { rows -> rows.toAssetGeometry() }

    /** The name of the group an asset belongs to, or null when it stands alone. */
    fun observeGroupName(assetId: Long): Flow<String?> = assetDao.observeGroupName(assetId)

    suspend fun getAsset(assetId: Long): AssetEntity? = assetDao.getAsset(assetId)

    suspend fun getAssetGeometry(assetId: Long): AssetGeometry =
        assetDao.getGeometry(assetId).toAssetGeometry()

    /**
     * Every asset's geometry, keyed by asset id, in one query.
     *
     * The two whole-farm documents the desk reads both need this: the GeoJSON is the geometry, and
     * the state document needs it to say which version of a line a card was handed. An asset with no
     * geometry simply has no key, which the callers read as "nothing drawn" - the same as an empty
     * [AssetGeometry] from [getAssetGeometry].
     */
    suspend fun allAssetGeometry(): Map<Long, AssetGeometry> =
        assetDao.allGeometry()
            .groupBy(keySelector = { it.assetId }, valueTransform = { it })
            .mapValues { (_, rows) -> rows.toAssetGeometry() }

    /** How many recordings name this asset. Part of what a delete would take with it. */
    suspend fun recordingCountFor(assetId: Long): Int = recordingDao.countForAsset(assetId)

    /**
     * How many recordings name each asset, for the whole farm at once.
     *
     * The document's own version of [recordingCountFor]: every asset on the desk carries a sentence
     * about what deleting it would take, and one read of the recordings says that for all of them.
     * Assets nothing is recorded against are simply absent, which the caller reads as none - the same
     * as a zero from the single-asset count.
     */
    suspend fun recordingCounts(): Map<Long, Int> =
        recordingDao.assetIds().groupingBy { it }.eachCount()

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
     * The sprays of an asset that could still colour part of its line.
     *
     * A spray older than the asset's own interval is left out, because whatever part of the
     * line it covered is due again anyway, and a part that nothing covers is drawn as still
     * to spray - which is the same answer. Reading every pass a track has ever had would
     * mean loading years of fixes to draw the same picture.
     *
     * The passes are the sprays that carry a recording, since a recording is the only thing
     * that says which part of the line went out. A spray logged by hand has no fixes behind
     * it and is reported as [AssetSprayCoverage.lastWithoutRecordingAtEpochMs] instead.
     */
    suspend fun getSprayCoverage(assetId: Long): AssetSprayCoverage {
        val asset = assetDao.getAsset(assetId) ?: return AssetSprayCoverage.NONE
        val since = System.currentTimeMillis() -
            (asset.intervalDays.toLong() + AssetEntity.DEFAULT_LEAD_DAYS) * MILLIS_PER_DAY
        val events = sprayEventDao.eventsSince(assetId, since)
        if (events.isEmpty()) return AssetSprayCoverage.NONE

        return AssetSprayCoverage(
            passes = events.mapNotNull { event ->
                event.recordedSessionId?.let { sessionId ->
                    RecordedPass(
                        atEpochMs = event.sprayedAtEpochMs,
                        points = recordingDao.getPoints(sessionId).map { it.toGeoPoint() },
                        // A pass read here has to be the same pass the recorder showed: the
                        // ground it was paused over stays red, and the gaps where the fixes
                        // only went missing are ground it drove.
                        breaks = recordingDao.getBreaks(sessionId).map { it.toRecordingBreak() },
                        // And the operator's word, where they had to give it: a pass whose second
                        // side the fixes could not vouch for says so, and reads as the second
                        // pass - see TwoPasses.
                        bothSidesClaimed =
                            recordingDao.getSession(sessionId)?.bothSidesClaimed == true
                    )
                }
            },
            lastWithoutRecordingAtEpochMs = events
                .lastOrNull { it.recordedSessionId == null }
                ?.sprayedAtEpochMs
        )
    }

    /**
     * Creates an asset, stores its planned geometry and computes its length.
     *
     * The group is given by name rather than id because that is what the operator
     * types or reads off a backup; an unknown name becomes a new group, which is what
     * "put this in the Estuary block" means when the block is new.
     *
     * This is the **one-line** form: a GPX file, a desk's new track and a test all have a line and
     * nothing else. A track with side tracks goes in through the [AssetGeometry] overload below, which
     * is the same nine arguments - the two are one door with two words for what is being carried, and
     * they are kept next to each other so they cannot drift apart.
     */
    suspend fun createAsset(
        name: String,
        geometry: List<GeoPoint>,
        kind: AssetKind = AssetKind.TRACK,
        shape: AssetShape = AssetShape.LINE,
        method: SprayMethod = SprayMethod.UNSET,
        notes: String? = null,
        groupName: String? = null,
        intervalDays: Int = AssetEntity.DEFAULT_INTERVAL_DAYS,
        swathWidthM: Double? = null,
        passesRequired: Int = AssetEntity.DEFAULT_PASSES_REQUIRED,
        passSeparationM: Double? = null,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long = createAsset(
        name = name,
        geometry = AssetGeometry.of(geometry),
        kind = kind,
        shape = shape,
        method = method,
        notes = notes,
        groupName = groupName,
        intervalDays = intervalDays,
        swathWidthM = swathWidthM,
        passesRequired = passesRequired,
        passSeparationM = passSeparationM,
        createdAtEpochMs = createdAtEpochMs
    )

    /** The same, for a track that is a line with side tracks hanging off it: what the drawing saves. */
    suspend fun createAsset(
        name: String,
        geometry: AssetGeometry,
        kind: AssetKind = AssetKind.TRACK,
        shape: AssetShape = AssetShape.LINE,
        method: SprayMethod = SprayMethod.UNSET,
        notes: String? = null,
        groupName: String? = null,
        intervalDays: Int = AssetEntity.DEFAULT_INTERVAL_DAYS,
        swathWidthM: Double? = null,
        passesRequired: Int = AssetEntity.DEFAULT_PASSES_REQUIRED,
        passSeparationM: Double? = null,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): Long = insertAsset(
        asset = AssetEntity(
            name = name,
            kind = kind.name,
            shape = shape.name,
            method = method.name,
            notes = notes,
            intervalDays = intervalDays,
            swathWidthM = swathWidthM,
            passesRequired = passesRequired,
            passSeparationM = passSeparationM,
            createdAtEpochMs = createdAtEpochMs
        ),
        geometry = geometry,
        groupName = groupName
    )

    /**
     * Inserts an asset that has already been built, with its geometry, in one transaction.
     *
     * Here for the desk's new track, which arrives as a row [nz.mckenzie.sprayday.ui.AssetEdits] has
     * already judged rather than as nine loose arguments - and it is the better home for the id, the
     * group and the length anyway: one place issues an id, and one place writes a new asset's geometry
     * and its cached length together, so a row and the length the lists read can never disagree.
     *
     * Any id on the row is dropped: a new asset's number is the database's to give, and a caller that
     * passed one - a page, or a desk building its body from a record it read - must not be able to
     * choose it.
     */
    suspend fun insertAsset(
        asset: AssetEntity,
        geometry: AssetGeometry,
        groupName: String?
    ): Long = db.withTransaction {
        val assetId = assetDao.insert(asset.copy(id = 0L, groupId = groupIdFor(groupName)))
        storeGeometry(assetId, geometry)
        assetId
    }

    /**
     * Writes an edited asset back, resolving the group name the form collected.
     *
     * Kept apart from [updateAsset] on purpose: this one is allowed to move an asset
     * between groups (including out of one, when the name is blank), while the plain
     * update must leave the grouping exactly as it found it.
     *
     * A geometry is written *with* the row rather than as a second call, because a desk moving a
     * vertex sends the whole line and the details it is looking at in one write: done in two, a failure
     * between them would leave an asset whose name and whose length disagree about which shape it is,
     * and nothing would ever say so.
     */
    suspend fun saveAssetEdits(
        asset: AssetEntity,
        groupName: String?,
        geometry: AssetGeometry? = null
    ) = db.withTransaction {
        assetDao.update(asset.copy(groupId = groupIdFor(groupName)))
        if (geometry != null) storeGeometry(asset.id, geometry)
    }

    suspend fun updateAsset(asset: AssetEntity) = assetDao.update(asset)

    suspend fun replaceGeometry(assetId: Long, geometry: AssetGeometry) = db.withTransaction {
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

    /**
     * Writes every path of an asset's geometry and the length that goes with it.
     *
     * The length is [AssetGeometry.lengthM] - every metre of every path, once - rather than a sum the
     * caller worked out: a cached length that disagrees with the vertices beside it is a list that
     * says a track is 800 m when its spur is 200 m of it.
     */
    private suspend fun storeGeometry(assetId: Long, geometry: AssetGeometry) {
        val rows = geometry.paths.flatMapIndexed { pathIndex, path ->
            path.mapIndexed { index, point ->
                AssetPointEntity(
                    assetId = assetId,
                    pathIndex = pathIndex,
                    sequence = index,
                    lat = point.lat,
                    lng = point.lng
                )
            }
        }
        assetDao.replaceGeometry(assetId, rows, geometry.lengthM)
    }

    // --- Blocks -----------------------------------------------------------------------

    /** Every block, including one whose assets have all been taken back out of it. */
    fun observeGroups(): Flow<List<GroupEntity>> = groupDao.observeGroups()

    /** The names of the blocks, for suggesting one rather than starting a second by typo. */
    fun observeBlockNames(): Flow<List<String>> = groupDao.observeNames()

    fun observeGroup(id: Long): Flow<GroupEntity?> = groupDao.observeGroup(id)

    /**
     * Renames a block and writes its notes, in one transaction.
     *
     * The name has already been checked against the other blocks by
     * [nz.mckenzie.sprayday.ui.BlockEdits]; what is left here is the write, so a rename cannot
     * land half-done.
     */
    suspend fun saveBlockEdits(id: Long, name: String, notes: String?) = db.withTransaction {
        groupDao.rename(id, name)
        groupDao.setNotes(id, notes)
    }

    /**
     * Deletes a block, leaving its assets exactly where they are.
     *
     * Which is the whole promise of the button: a block is a way of working, not a container
     * that things live inside.
     */
    suspend fun deleteBlock(id: Long) = db.withTransaction {
        groupDao.detachAssets(id)
        groupDao.delete(id)
    }

    // --- GPX interchange ------------------------------------------------------------

    /** Exports an asset's planned geometry as GPX 1.1, or null if the asset is gone. */
    suspend fun exportAssetGpx(assetId: Long): String? {
        val asset = assetDao.getAsset(assetId) ?: return null
        return GpxWriter.write(asset.name, getAssetGeometry(assetId).paths)
    }

    /** Imports a GPX file as a new track. Throws if it has fewer than two points. */
    suspend fun importAssetGpx(
        name: String,
        gpx: String,
        createdAtEpochMs: Long = System.currentTimeMillis()
    ): GpxImportResult {
        val segments = GpxParser.parseSegments(gpx)
        val line = segments.firstOrNull().orEmpty()
        val sideTracks = segments.drop(1)

        // A file whose later segments each start on the first is a track with side tracks, which is
        // what a GPX with several `<trkseg>` is for - a fence and the spur into the gully, exported
        // from a tool that knows about both. The join has to be exact, because that is what the app's
        // own drawing produces and what its rules ask for.
        val joined = sideTracks.isNotEmpty() &&
            sideTracks.all { side -> side.size >= 2 && line.any { vertex -> vertex == side.first() } }

        if (joined) {
            val id = createAsset(
                name = name,
                geometry = AssetGeometry.of(line, sideTracks),
                createdAtEpochMs = createdAtEpochMs
            )
            return GpxImportResult(id, sideTracks = sideTracks.size, segmentsDidNotJoin = false)
        }

        // Otherwise the file is read the way this app has always read one: every track point in
        // document order, joined up, as a single line. A file whose segments do not meet is not a
        // refusal - it is a file from a tool that cuts a line up for its own reasons - but the answer
        // says so, because a track with a jump in it is worth knowing about.
        val geometry = GpxParser.parse(gpx)
        require(geometry.size >= 2) { "A line needs at least two points" }
        val id = createAsset(name = name, geometry = geometry, createdAtEpochMs = createdAtEpochMs)
        return GpxImportResult(id, sideTracks = 0, segmentsDidNotJoin = segments.size > 1)
    }
}

/**
 * What an imported GPX file turned out to hold.
 *
 * [sideTracks] is how many of the file's own track segments became side tracks on the one track it
 * made, and [segmentsDidNotJoin] is true when they could not - in which case the file was read the way
 * this app has always read one, every point joined into a single line, and the sentence the operator
 * gets says so rather than the import failing or the difference going unmentioned.
 */
data class GpxImportResult(
    val assetId: Long,
    val sideTracks: Int = 0,
    val segmentsDidNotJoin: Boolean = false
)

private fun AssetPointEntity.toGeoPoint() = GeoPoint(lat = lat, lng = lng)

/**
 * Rows back into geometry, a path at a time.
 *
 * The query orders by path and then by position, so this needs no sorting of its own: `groupBy` keeps
 * the order it meets things in, which is what makes path 0 the line and path 1 the first side track
 * rather than whichever order a map happened to iterate in.
 */
private fun List<AssetPointEntity>.toAssetGeometry(): AssetGeometry {
    if (isEmpty()) return AssetGeometry.NONE
    return AssetGeometry(groupBy { it.pathIndex }.map { (_, rows) -> rows.map { it.toGeoPoint() } })
}

/**
 * For the window of sprays the map reads. A day of exactly 24 hours is close enough for a
 * window: which sprays are inside it decides what the map *looks at*, never what it says -
 * an hour either side of the edge colours a stretch the same way.
 */
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
