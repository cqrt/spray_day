package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Extremes of every planned asset's geometry. Null columns when there is no geometry
 * at all, which is how "nothing planned yet" is told apart from "assets at 0,0".
 */
data class AssetPointBounds(
    val minLat: Double?,
    val minLng: Double?,
    val maxLat: Double?,
    val maxLng: Double?
)

/**
 * The name of the block an asset is in, for the list to fold assets into blocks with.
 *
 * An asset with no group simply has no row here, which is how "on its own" is told apart
 * from "in a block" without a null column to interpret.
 */
data class AssetGroupName(
    val assetId: Long,
    val name: String
)

/**
 * Declared as an abstract class rather than an interface so that
 * [replaceGeometry] can be a real transactional method - Kotlin interface
 * default methods need extra compiler flags to work reliably with Room.
 */
@Dao
abstract class AssetDao {
    @Query("SELECT * FROM assets WHERE active = 1 ORDER BY name COLLATE NOCASE")
    abstract fun observeActiveAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets ORDER BY name COLLATE NOCASE")
    abstract fun observeAllAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id")
    abstract fun observeAsset(id: Long): Flow<AssetEntity?>

    @Query("SELECT * FROM assets WHERE id = :id")
    abstract suspend fun getAsset(id: Long): AssetEntity?

    /**
     * The name of the group an asset belongs to, or null when it stands alone.
     *
     * The edit form asks for this rather than the id, because the operator edits the
     * name they can see; the id is resolved when the form is saved.
     */
    @Query("SELECT g.name FROM groups g JOIN assets a ON a.groupId = g.id WHERE a.id = :assetId")
    abstract fun observeGroupName(assetId: Long): Flow<String?>

    /**
     * Every asset's block name, for folding the list into blocks.
     *
     * One query for the whole list rather than a lookup per row: the list already loads every
     * asset to colour it, and asking the database once per row to say which block it is in
     * would be a query per asset for a name that is a join away.
     */
    @Query("SELECT a.id AS assetId, g.name AS name FROM assets a JOIN groups g ON a.groupId = g.id")
    abstract fun observeAssetGroupNames(): Flow<List<AssetGroupName>>

    @Insert
    abstract suspend fun insert(asset: AssetEntity): Long

    @Update
    abstract suspend fun update(asset: AssetEntity)

    @Query("DELETE FROM assets WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query(
        """
        UPDATE assets
        SET lastSprayedAtEpochMs = MAX(COALESCE(lastSprayedAtEpochMs, 0), :sprayedAtEpochMs)
        WHERE id = :assetId
        """
    )
    abstract suspend fun setLastSprayedAt(assetId: Long, sprayedAtEpochMs: Long)

    @Query("UPDATE assets SET lengthM = :lengthM WHERE id = :assetId")
    abstract suspend fun updateLength(assetId: Long, lengthM: Double)

    @Query("SELECT * FROM asset_points WHERE assetId = :assetId ORDER BY sequence")
    abstract suspend fun getGeometry(assetId: Long): List<AssetPointEntity>

    /**
     * The box containing every planned asset, for answering "where is the work?" with
     * one query rather than loading every point.
     */
    @Query(
        "SELECT MIN(lat) AS minLat, MIN(lng) AS minLng, " +
            "MAX(lat) AS maxLat, MAX(lng) AS maxLng FROM asset_points"
    )
    abstract suspend fun pointBounds(): AssetPointBounds?

    @Query("SELECT * FROM asset_points WHERE assetId = :assetId ORDER BY sequence")
    abstract fun observeGeometry(assetId: Long): Flow<List<AssetPointEntity>>

    @Insert
    abstract suspend fun insertGeometry(points: List<AssetPointEntity>)

    @Query("DELETE FROM asset_points WHERE assetId = :assetId")
    abstract suspend fun deleteGeometry(assetId: Long)

    /** Swaps an asset's geometry and refreshes its cached length atomically. */
    @androidx.room.Transaction
    open suspend fun replaceGeometry(assetId: Long, points: List<AssetPointEntity>, lengthM: Double) {
        deleteGeometry(assetId)
        if (points.isNotEmpty()) insertGeometry(points)
        updateLength(assetId, lengthM)
    }
}
