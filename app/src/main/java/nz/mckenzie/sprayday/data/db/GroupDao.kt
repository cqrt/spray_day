package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Groups: the named collections assets are worked in.
 *
 * [findByName] is the one that matters to the rest of the app. A name typed into the
 * edit form, or read out of a backup written before groups existed, has to resolve to
 * the same group as last time rather than quietly creating a near-duplicate. The
 * comparison is case-insensitive because the column is, so "estuary" finds "Estuary".
 */
@Dao
interface GroupDao {

    @Query("SELECT * FROM groups WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): GroupEntity?

    /**
     * Returns the new row id, or -1 when a group of that name already exists - which is
     * why callers look the name up again rather than trusting the id.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(group: GroupEntity): Long

    /** Every block, by name, for the screen that keeps them tidy. */
    @Query("SELECT * FROM groups ORDER BY name COLLATE NOCASE")
    fun observeGroups(): Flow<List<GroupEntity>>

    /**
     * Just the names, for suggesting a block that already exists rather than letting a
     * misspelling quietly start a new one.
     */
    @Query("SELECT name FROM groups ORDER BY name COLLATE NOCASE")
    fun observeNames(): Flow<List<String>>

    @Query("SELECT * FROM groups WHERE id = :id")
    fun observeGroup(id: Long): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :id")
    suspend fun getGroup(id: Long): GroupEntity?

    @Query("UPDATE groups SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE groups SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: Long, notes: String?)

    /**
     * Takes every asset out of a block without touching the assets themselves.
     *
     * The foreign key already clears the pointer when a group is deleted, but this says so
     * out loud rather than relying on a pragma: a block going away must never take a season's
     * worth of assets with it, and the intent is worth reading in the query.
     */
    @Query("UPDATE assets SET groupId = NULL WHERE groupId = :id")
    suspend fun detachAssets(id: Long)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: Long)
}
