package nz.mckenzie.sprayday.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod

/**
 * Something the operator sprays: a track, a road, or a piece of infrastructure.
 *
 * The word is deliberately wider than "track". The same list now holds a stopbank to
 * blanket, an estuary road to spot-spray and a picnic table to wipe over, and those
 * differ only in [kind], [method] and whether they are a line or a place.
 *
 * [lastSprayedAtEpochMs] is denormalised from [SprayEventEntity] so the map can colour
 * every asset without joining the spray history on each frame. It is maintained by the
 * repository whenever a spray event is recorded.
 */
@Entity(
    tableName = "assets",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("active"), Index("lastSprayedAtEpochMs"), Index("groupId")]
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** [AssetKind] name. Stored as text so an unknown value cannot break the map. */
    val kind: String = AssetKind.TRACK.name,
    /** [AssetShape] name: a line, a single point, or a ring of ground with an edge. */
    val shape: String = AssetShape.LINE.name,
    /** [SprayMethod] name. [SprayMethod.UNSET] until the operator says otherwise. */
    val method: String = SprayMethod.UNSET.name,
    /** The group this asset is worked with, if any. Null is "on its own". */
    val groupId: Long? = null,
    val notes: String? = null,
    /** Target days between sprays; 120 is roughly three times a year. */
    val intervalDays: Int = DEFAULT_INTERVAL_DAYS,
    /** Used to estimate treated area: length x swath width. */
    val swathWidthM: Double? = null,
    /**
     * How many passes along the line the job takes: one, or two for a line that is sprayed
     * up one side and back down the other.
     *
     * A line sprayed twice is not half sprayed when the first pass ends - it is not sprayed
     * yet - and this is what says so. It is a property of the place, like [method]: the
     * estuary road is always done both edges, the shelter belt is always done once.
     *
     * One by default, which is what every asset in the database was before this existed and
     * what the app has always assumed, so nothing behaves differently for not having said.
     */
    val passesRequired: Int = DEFAULT_PASSES_REQUIRED,
    /**
     * How far apart the two passes run, in metres, when the operator has said.
     *
     * Used for one thing: deciding whether the app can tell which pass was on which side.
     * Three metres on a road is two strips it can tell apart; a metre apart on a knapsack
     * track is not, and then the app goes by which way each pass was heading and asks the
     * operator when even that is no help. Null means nobody has said, which reads the same
     * way as "too close to tell".
     */
    val passSeparationM: Double? = null,
    val active: Boolean = true,
    val createdAtEpochMs: Long,
    val lastSprayedAtEpochMs: Long? = null,
    /** Cached geometry length so lists and the due engine need no point joins. */
    val lengthM: Double = 0.0,

    /**
     * The ground the shape encloses, in square metres, for a kind that is ground with an edge.
     *
     * Cached from the vertices beside [lengthM] and refreshed by the same write, for the same reason:
     * a block's tile is added up from rows rather than from geometry, and a carpark's ground is a
     * **measurement** - the one area in this app that is not an estimate from a swath width. Zero for
     * every other kind, which is what a line and a place enclose.
     */
    val areaM2: Double = 0.0
) {

    /**
     * The ground this row's shape encloses, or null when it encloses none.
     *
     * The row's own answer, in one place, so the card, a block's tile and a recorded spray cannot each
     * work it out slightly differently - and null rather than zero for a line and a place, because a
     * zero area beside a real one is the kind of figure that gets added up and believed.
     */
    val groundSqm: Double?
        get() = if (AssetShape.fromStorage(shape) == AssetShape.AREA) areaM2.takeIf { it > 0.0 } else null

    companion object {
        const val DEFAULT_INTERVAL_DAYS = 120

        /** Days before the due date at which an asset turns yellow. */
        const val DEFAULT_LEAD_DAYS = 14

        /** One pass is the job unless the operator says otherwise. */
        const val DEFAULT_PASSES_REQUIRED = 1

        /** The most passes a line can be said to need: up and back, or both sides. */
        const val MAX_PASSES_REQUIRED = 2

        /** A line that is sprayed twice. */
        const val TWO_PASSES_REQUIRED = 2
    }
}
