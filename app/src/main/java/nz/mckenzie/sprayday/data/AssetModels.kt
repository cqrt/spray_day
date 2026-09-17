package nz.mckenzie.sprayday.data

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.due.DueInfo

/**
 * An asset plus everything the map and list need to render it: its traffic-light
 * due state, how many times it has been sprayed, and the block it is worked with.
 *
 * The block arrives as a name rather than an id because a name is what the list shows: the
 * assets in one block are folded together under it, and a rename should change that heading
 * without touching anything else.
 */
data class AssetWithDue(
    val asset: AssetEntity,
    val due: DueInfo,
    val sprayCount: Int,
    /** Null when the asset stands on its own. */
    val groupName: String? = null
)

/**
 * One line of a spray record: how much of a product went out.
 * This is what the operator enters before starting a spray.
 */
data class SprayProductQuantity(
    val productId: Long,
    val quantityMl: Double
)
