package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProductDao {

    @Query("SELECT * FROM products WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    abstract fun observeActive(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE")
    abstract fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    abstract suspend fun getById(id: Long): ProductEntity?

    /** Ignored on conflict so re-adding the same product name is harmless. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(product: ProductEntity): Long

    @Update
    abstract suspend fun update(product: ProductEntity)

    @Query("UPDATE products SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    /**
     * Renames a product. The name column is unique, so renaming onto an existing
     * product name fails rather than silently merging two chemicals.
     */
    @Query("UPDATE products SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)
}
