package nz.mckenzie.sprayday.data

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.due.DueInfo

/**
 * An asset plus everything the map and list need to render it: its traffic-light
 * due state and how many times it has been sprayed.
 */
data class AssetWithDue(
    val asset: AssetEntity,
    val due: DueInfo,
    val sprayCount: Int
)

/**
 * One line of a spray record: how much of a product went out.
 * This is what the operator enters before starting a spray.
 */
data class SprayProductQuantity(
    val productId: Long,
    val quantityMl: Double
)
