package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recorded spray of one asset: when it happened, how much of each product went
 * out, and optionally the GPS session that proves the coverage.
 */
@Entity(
    tableName = "spray_events",
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("sprayedAtEpochMs")]
)
data class SprayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val assetId: Long,
    val sprayedAtEpochMs: Long,
    /** Tank/water volume used, if recorded. */
    val waterLitres: Double? = null,
    val operatorName: String? = null,
    val notes: String? = null,
    /** Actual length driven, which may differ from the planned geometry. */
    val distanceM: Double? = null,
    val areaSqm: Double? = null,
    /** Links to the GPS recording for this spray, once recording is wired up. */
    val recordedSessionId: Long? = null
)

/**
 * Quantity of one product used in one spray event. This is the "mL of each
 * product" the operator enters before starting.
 */
@Entity(
    tableName = "spray_event_products",
    foreignKeys = [
        ForeignKey(
            entity = SprayEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["sprayEventId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sprayEventId"), Index("productId")]
)
data class SprayEventProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sprayEventId: Long,
    val productId: Long,
    val quantityMl: Double
)

/**
 * Pre-fills the spray entry form for an asset: "this one always gets 400 mL of
 * Product X". Null quantity means "show the product with an empty amount".
 */
@Entity(
    tableName = "asset_product_defaults",
    primaryKeys = ["assetId", "productId"],
    foreignKeys = [
        ForeignKey(
            entity = AssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("assetId"), Index("productId")]
)
data class AssetProductDefaultEntity(
    val assetId: Long,
    val productId: Long,
    val defaultQuantityMl: Double? = null
)
