package nz.mckenzie.sprayday.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named collection of assets: "Estuary" holding the estuary road, the lagoon and the
 * lower track, which are sprayed and reported on together.
 *
 * Names are unique regardless of case, so "Estuary" and "estuary" cannot both exist and
 * quietly split one block in two. That is what the NOCASE collation is for: the unique
 * index compares the way the operator thinks of the names, not byte by byte.
 *
 * Assets point at a group rather than storing its name, so renaming a group does not
 * rewrite every asset - and deleting one leaves the assets where they are, with their
 * group cleared ([AssetEntity] holds the foreign key with ON DELETE SET NULL).
 */
@Entity(
    tableName = "groups",
    indices = [Index(value = ["name"], unique = true)]
)
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val notes: String? = null
)
