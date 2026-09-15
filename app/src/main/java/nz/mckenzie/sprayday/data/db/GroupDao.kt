package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
}
