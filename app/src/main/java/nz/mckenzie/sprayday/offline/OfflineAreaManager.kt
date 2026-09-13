package nz.mckenzie.sprayday.offline

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import nz.mckenzie.sprayday.map.LinzBasemap
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.concurrent.ConcurrentHashMap

/** Progress of an offline area, as reported by MapLibre. */
data class OfflineArea(
    val id: Long,
    val name: String,
    val isComplete: Boolean,
    val completedResources: Long,
    val requiredResources: Long,
    val completedBytes: Long,
    /**
     * True when the download stopped making progress without reaching 100%.
     * MapLibre retries a resource the service never serves, indefinitely -
     * measured on an emulator, a 6 km area sat at 3,174 of 3,175 files
     * indefinitely (the missing file is most likely a tile LINZ has no imagery
     * for). The pack is perfectly usable at that point, so callers stop the
     * retry loop and report the shortfall honestly instead of hanging.
     */
    val stalled: Boolean = false
) {
    /** 0-100, or 100 once complete. Unknown totals report 0 rather than lying. */
    val percent: Int
        get() = when {
            isComplete -> 100
            requiredResources <= 0L -> 0
            else -> ((completedResources * 100) / requiredResources).toInt().coerceIn(0, 100)
        }

    val sizeLabel: String get() = formatBytes(completedBytes)

    /** Files the service never served (missing imagery, not a failure to fetch). */
    val missingResources: Long
        get() = (requiredResources - completedResources).coerceAtLeast(0L)

    /** Good enough to rely on in the field, even if a few tiles are missing. */
    val isUsable: Boolean get() = isComplete || (stalled && completedResources > 0L)
}

/**
 * Downloads basemap areas for use with no reception.
 *
 * MapLibre's OfflineManager requires a style *URL*: it cannot read a `file://`
 * path (verified - `Mbgl-HttpRequest: [HTTP] Unable to parse resourceUrl`), nor
 * inline JSON, so downloads are defined against LINZ's hosted aerial style.
 *
 * Two consequences worth knowing:
 *  - The hosted style's aerial tile template is identical to the one our live
 *    map uses, so cached tiles ARE served to the map when offline. That is the
 *    property the whole feature depends on, and it holds.
 *  - The hosted style also declares two `raster-dem` terrain sources that its
 *    own layer list never uses, and MapLibre walks every source in the style.
 *    Measured on an emulator: a 6 km area that needs ~304 aerial tiles (~13 MB)
 *    actually pulled 3,175 resources / 74.6 MB, and took a couple of minutes.
 *    The UI therefore reports the aerial estimate AND warns that the real
 *    download is larger. Fetching the tiles ourselves into an MBTiles pack and
 *    merging it with OfflineManager.mergeOfflineRegions() is the way to
 *    eliminate that overhead; it is deliberately deferred rather than rushed.
 */
class OfflineAreaManager(private val context: Context) {

    private val statuses = ConcurrentHashMap<Long, MutableStateFlow<OfflineArea>>()

    /** Creates the region and starts downloading it. */
    suspend fun startArea(plan: OfflineAreaPlan, apiKey: String): OfflineArea {
        val manager = OfflineManager.getInstance(context)
        // Cap tiles per region so a mis-sized plan cannot quietly become a
        // multi-gigabyte download, or a burst of requests against LINZ.
        manager.setOfflineMapboxTileCountLimit(MAX_TILES_PER_REGION)

        val definition = OfflineTilePyramidRegionDefinition(
            LinzBasemap.hostedAerialStyleUrl(apiKey),
            LatLngBounds.Builder()
                .include(LatLng(plan.bounds.minLat, plan.bounds.minLng))
                .include(LatLng(plan.bounds.maxLat, plan.bounds.maxLng))
                .build(),
            plan.minZoom.toDouble(),
            plan.maxZoom.toDouble(),
            context.resources.displayMetrics.density
        )

        val region = OfflineManager.getInstance(context)
            .createRegionAsync(definition, plan.name.toByteArray(Charsets.UTF_8))

        val initial = OfflineArea(
            id = region.id,
            name = plan.name,
            isComplete = false,
            completedResources = 0L,
            requiredResources = 0L,
            completedBytes = 0L
        )
        val state = MutableStateFlow(initial)
        statuses[region.id] = state

        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                state.value = status.toOfflineArea(region.id, plan.name)
            }

            override fun onError(error: OfflineRegionError) {
                // A stalled download is turned into a visible failure by the
                // caller's timeout rather than being hidden here.
                state.value = state.value.copy(isComplete = false)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                // The region blew past MAX_TILES_PER_REGION, so it will never
                // report complete - leaving it as an explicit stall.
                state.value = state.value.copy(isComplete = false)
            }
        })

        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        return initial
    }

    companion object {
        /** Roughly 360 MB at the 45 KB average tile size our estimates assume. */
        const val MAX_TILES_PER_REGION = 8_000L

        private const val POLL_MS = 1_000L
    }

    /** Progress stream for an area started in this session. */
    fun progress(regionId: Long): Flow<OfflineArea>? = statuses[regionId]

    /**
     * Waits for an area to finish downloading.
     *
     * Updates are pushed by MapLibre's observer, so this does not poll the
     * service - but it does watch for a *stall*: MapLibre retries resources the
     * server never serves (missing imagery) indefinitely, so once the resource
     * count stops moving the area is reported as stalled-but-usable instead of
     * hanging the caller. Throws [IllegalStateException] only if the deadline
     * passes with no progress at all.
     */
    suspend fun awaitComplete(
        regionId: Long,
        timeoutMs: Long,
        stallMillis: Long = 30_000L
    ): OfflineArea {
        val state = statuses[regionId]
            ?: error("No offline area with id $regionId in this session")

        val startedAt = System.currentTimeMillis()
        var lastCount = -1L
        var lastChangeAt = startedAt

        while (true) {
            val current = state.value
            if (current.isComplete) return current
            if (current.completedResources != lastCount) {
                lastCount = current.completedResources
                lastChangeAt = System.currentTimeMillis()
            }
            val now = System.currentTimeMillis()
            if (current.completedResources > 0L && now - lastChangeAt > stallMillis) {
                return current.copy(stalled = true)
            }
            if (now - startedAt > timeoutMs) {
                throw IllegalStateException(
                    "Download timed out after ${timeoutMs}ms with " +
                        "${current.completedResources}/${current.requiredResources} files"
                )
            }
            kotlinx.coroutines.delay(POLL_MS)
        }
    }

    /** Stops a stalled download so MapLibre stops retrying missing tiles. */
    suspend fun pauseArea(regionId: Long) {
        OfflineManager.getInstance(context)
            .listRegionsAsync()
            .firstOrNull { it.id == regionId }
            ?.setDownloadState(OfflineRegion.STATE_INACTIVE)
    }

    /** Areas already stored on the device, with the name we gave them. */
    suspend fun listAreas(): List<OfflineArea> =
        OfflineManager.getInstance(context).listRegionsAsync().map { region ->
            statuses[region.id]?.value ?: OfflineArea(
                id = region.id,
                name = region.metadata?.toString(Charsets.UTF_8).orEmpty().ifBlank { "Offline area" },
                isComplete = false,
                completedResources = 0L,
                requiredResources = 0L,
                completedBytes = 0L
            )
        }

    suspend fun deleteArea(regionId: Long) {
        OfflineManager.getInstance(context)
            .listRegionsAsync()
            .firstOrNull { it.id == regionId }
            ?.deleteAsync()
        statuses.remove(regionId)
    }
}
