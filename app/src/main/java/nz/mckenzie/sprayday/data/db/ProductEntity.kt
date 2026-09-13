package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chemical or additive in the catalogue, so sprays can be recorded as
 * "1.2 L of Product X" rather than free text.
 */
@Entity(
    tableName = "products",
    indices = [Index(value = ["name"], unique = true)]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** Unit the quantity is recorded in. Millilitres by default. */
    val unit: String = UNIT_ML,
    /** Optional guidance shown when recording a spray, e.g. "10 mL/L". */
    val rateText: String? = null,
    val notes: String? = null,
    /** Archived products stay in history but are hidden from pickers. */
    val archived: Boolean = false
) {
    companion object {
        const val UNIT_ML = "mL"
    }
}
