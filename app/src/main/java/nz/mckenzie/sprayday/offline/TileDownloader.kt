package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.domain.tiles.TileMath

/** Fetches one tile. Injected so the downloader can be tested without a network. */
fun interface TileFetcher {
    sealed interface Result {
        /** The tile arrived. */
        data class Tile(val bytes: ByteArray) : Result

        /** The service has no imagery for this tile. Not an error. */
        data object NotFound : Result

        /** The request failed, even after retries. */
        data class Failed(val message: String) : Result
    }

    suspend fun fetch(zoom: Int, x: Int, y: Int): Result
}

/**
 * Downloads the aerial pyramid for an area, tile by tile.
 *
 * Written to replace MapLibre's OfflineManager for imagery, which (when pointed
 * at LINZ's hosted style) also pulled two unused terrain sources - measured at
 * 3,175 resources for an area that needs ~304 aerial tiles. This downloads
 * exactly the tiles the plan calls for, nothing else, and reports exact progress.
 *
 * Interruption is safe: tiles already on disk are skipped, so a second run
 * resumes rather than restarts.
 */
class TileDownloader(
    private val store: OfflineTileStore,
    private val fetcher: TileFetcher,
    private val concurrency: Int = 4,
    /** Kept well inside LINZ's 1,000 requests/minute standard-access limit. */
    private val maxRequestsPerMinute: Int = 600,
    private val retries: Int = 2,
    private val maxTiles: Int = 12_000
) {

    data class Progress(
        /** Tiles now on disk for this area, including any that were already there. */
        val stored: Int,
        val total: Int,
        val bytes: Long,
        /** Tiles the service has no imagery for (usually the edge of coverage). */
        val unavailable: Int,
        /** Tiles that failed after retries. */
        val failed: Int
    ) {
        val percent: Int get() = if (total <= 0) 100 else ((stored * 100) / total).coerceIn(0, 100)
    }

    /**
     * Pacing spreads the target rate across the workers, so the aggregate stays
     * at [maxRequestsPerMinute] regardless of how fast the network is.
     */
    private val pacingDelayMs: Long =
        ((60_000.0 / maxRequestsPerMinute) / concurrency).toLong().coerceAtLeast(0L)

    suspend fun download(
        bounds: LatLngBounds,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (Progress) -> Unit = {}
    ): Progress = coroutineScope {
        val planned = plannedTiles(bounds, minZoom, maxZoom)
        require(planned.size <= maxTiles) {
            "That area needs ${planned.size} tiles, more than the $maxTiles limit. " +
                "Narrow the zoom range or the area."
        }

        val present = planned.filter { store.contains(it.zoom, it.x, it.y) }
        var stored = present.size
        var bytes = present.sumOf { store.tileFile(it.zoom, it.x, it.y).length() }
        var unavailable = 0
        var failed = 0

        val remaining = planned.filterNot { store.contains(it.zoom, it.x, it.y) }
        onProgress(Progress(stored, planned.size, bytes, unavailable, failed))

        remaining.chunked(concurrency).forEach { chunk ->
            chunk
                .map { tile -> async(Dispatchers.IO) { tile to fetchWithRetries(tile) } }
                .awaitAll()
                .forEach { (tile, result) ->
                    when (result) {
                        is TileFetcher.Result.Tile -> {
                            bytes += store.write(tile.zoom, tile.x, tile.y, result.bytes)
                            stored++
                        }

                        TileFetcher.Result.NotFound -> unavailable++
                        is TileFetcher.Result.Failed -> failed++
                    }
                    if (pacingDelayMs > 0L) delay(pacingDelayMs)
                }
            onProgress(Progress(stored, planned.size, bytes, unavailable, failed))
        }

        Progress(stored, planned.size, bytes, unavailable, failed)
    }

    private suspend fun fetchWithRetries(tile: TileRef): TileFetcher.Result {
        var last: TileFetcher.Result = TileFetcher.Result.Failed("not attempted")
        repeat(retries + 1) { attempt ->
            last = runCatching { fetcher.fetch(tile.zoom, tile.x, tile.y) }
                .getOrElse { error -> TileFetcher.Result.Failed(error.message ?: "fetch failed") }
            if (last !is TileFetcher.Result.Failed) return last
            if (attempt < retries) delay(BACKOFF_MS * (attempt + 1))
        }
        return last
    }

    /** Every tile covering [bounds] across the inclusive zoom range. */
    fun plannedTiles(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): List<TileRef> {
        if (maxZoom < minZoom) return emptyList()
        val tiles = ArrayList<TileRef>(TileMath.tileCount(bounds, minZoom, maxZoom).toInt().coerceAtLeast(0))
        for (zoom in minZoom..maxZoom) {
            val range = TileMath.tileRange(bounds, zoom)
            for (x in range.minX..range.maxX) {
                for (y in range.minY..range.maxY) {
                    tiles += TileRef(zoom, x, y)
                }
            }
        }
        return tiles
    }

    private companion object {
        const val BACKOFF_MS = 250L
    }
}
