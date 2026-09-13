package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns MapLibre's callback-based offline API into suspend functions.
 *
 * MapLibre reports failures as strings rather than exceptions, so they are
 * wrapped in [IllegalStateException] - callers that care can catch it, and
 * callers that do not still get a loud failure instead of a silent stall.
 */
internal suspend fun OfflineManager.createRegionAsync(
    definition: OfflineTilePyramidRegionDefinition,
    metadata: ByteArray
): OfflineRegion = suspendCancellableCoroutine { continuation ->
    createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
        override fun onCreate(offlineRegion: OfflineRegion) {
            if (continuation.isActive) continuation.resume(offlineRegion)
        }

        override fun onError(error: String) {
            if (continuation.isActive) {
                continuation.resumeWithException(IllegalStateException(error))
            }
        }
    })
}

internal suspend fun OfflineManager.listRegionsAsync(): List<OfflineRegion> =
    suspendCancellableCoroutine { continuation ->
        listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                if (continuation.isActive) continuation.resume(regions?.toList().orEmpty())
            }

            override fun onError(error: String) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            }
        })
    }

internal suspend fun OfflineRegion.deleteAsync(): Unit = suspendCancellableCoroutine { continuation ->
    delete(object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() {
            if (continuation.isActive) continuation.resume(Unit)
        }

        override fun onError(error: String) {
            if (continuation.isActive) {
                continuation.resumeWithException(IllegalStateException(error))
            }
        }
    })
}

internal fun OfflineRegionStatus.toOfflineArea(regionId: Long, name: String) = OfflineArea(
    id = regionId,
    name = name,
    isComplete = isComplete,
    completedResources = completedResourceCount,
    requiredResources = requiredResourceCount,
    completedBytes = completedResourceSize
)
