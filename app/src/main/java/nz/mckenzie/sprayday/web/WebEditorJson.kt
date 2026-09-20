package nz.mckenzie.sprayday.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.backup.AssetRecord
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/**
 * What the page is told about the work: the document behind `GET /api/state`.
 *
 * It is the backup's own vocabulary - [AssetRecord], [GroupRecord], [ProductRecord], already
 * versioned, already restored by an older build and read by a newer one - rather than a second
 * asset shape invented for the web. A second shape is a second mapping to keep right, and the one
 * that would quietly fall behind is the one nobody looks at. What is added on top is the view
 * fields the page cannot work out for itself: which traffic light the asset is under, and when it
 * is next due, both decided by [nz.mckenzie.sprayday.domain.due.DueCalculator] on the phone.
 *
 * **The geometry is not in here.** It travels once, in the GeoJSON the style's own source reads
 * (`/api/assets.geojson`, built by [nz.mckenzie.sprayday.map.AssetGeoJson]) - the same document the
 * app's own map draws, so the desk and the phone cannot disagree about where a line goes. A record
 * here carries `points` empty, and its `lengthM` still says how long it is.
 *
 * The clock is the phone's, not the laptop's: `nowEpochMs` is what every due colour in the document
 * was worked out against, so a page whose owner has their clock a day out still agrees with the
 * tractor about what is due.
 */
object WebEditorJson {

    /**
     * Defaults and nulls are written out rather than left off, so the page is handed every key
     * every time: a field that is sometimes absent is a field the page's own code has to guess
     * about, and guessing is what a document is for.
     */
    private val json = Json { encodeDefaults = true }

    fun build(document: WebEditorDocument): String = json.encodeToString(WebEditorDocument.serializer(), document)

    /**
     * One asset as the wire carries it.
     *
     * The mapping is written out here rather than reached for in the backup, where the same
     * mapping is private, and it is pinned field by field by this file's test: a column added to
     * the asset table and forgotten here would be a column the desk never sees, which is a quiet
     * way to lose something the operator typed on the phone.
     */
    fun record(
        asset: AssetEntity,
        due: DueInfo,
        sprayCount: Int,
        groupName: String?
    ): WebEditorAssetRecord = WebEditorAssetRecord(
        asset = AssetRecord(
            id = asset.id,
            name = asset.name,
            groupId = asset.groupId,
            kind = asset.kind,
            shape = asset.shape,
            method = asset.method,
            notes = asset.notes,
            intervalDays = asset.intervalDays,
            swathWidthM = asset.swathWidthM,
            passesRequired = asset.passesRequired,
            passSeparationM = asset.passSeparationM,
            active = asset.active,
            createdAtEpochMs = asset.createdAtEpochMs,
            lastSprayedAtEpochMs = asset.lastSprayedAtEpochMs,
            lengthM = asset.lengthM,
            points = emptyList()
        ),
        dueStatus = due.status.name,
        dueAtEpochMs = due.dueDateEpochMs,
        daysUntilDue = due.daysUntilDue,
        sprayCount = sprayCount,
        groupName = groupName
    )
}

/** Everything `GET /api/state` answers with. */
@Serializable
data class WebEditorDocument(
    /** The phone's clock, which every due status in this document was worked out against. */
    val nowEpochMs: Long,
    /** The active assets. An archived one is not on the map the operator works from. */
    val assets: List<WebEditorAssetRecord> = emptyList(),
    val groups: List<GroupRecord> = emptyList(),
    /** Read-only on the desk: the catalogue is the phone's business. */
    val products: List<ProductRecord> = emptyList(),
    /** The box the work is in, so the page can open on the farm rather than on the ocean. */
    val bounds: WebEditorBounds? = null,
    /** The phone's last fix, if it has one. Better than the browser's own: see the plan. */
    val position: WebEditorPosition? = null
)

/** One asset, with the traffic light the page paints it in. */
@Serializable
data class WebEditorAssetRecord(
    val asset: AssetRecord,
    /** [nz.mckenzie.sprayday.domain.due.DueStatus] name, e.g. `DUE_SOON`. */
    val dueStatus: String,
    /** The start of the day it is next due, phone's time zone; null when never sprayed. */
    val dueAtEpochMs: Long? = null,
    /** Negative when overdue, null when never sprayed, so the page can word it without arithmetic. */
    val daysUntilDue: Long? = null,
    val sprayCount: Int = 0,
    val groupName: String? = null
)

/** A box on the ground, in the order a person would say it. */
@Serializable
data class WebEditorBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double
) {
    companion object {
        fun of(bounds: LatLngBounds) = WebEditorBounds(
            minLat = bounds.minLat,
            minLng = bounds.minLng,
            maxLat = bounds.maxLat,
            maxLng = bounds.maxLng
        )
    }
}

/** Where the phone was when it last knew. */
@Serializable
data class WebEditorPosition(val lat: Double, val lng: Double)
