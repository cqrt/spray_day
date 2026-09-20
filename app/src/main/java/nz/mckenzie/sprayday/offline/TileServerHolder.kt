package nz.mckenzie.sprayday.offline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.domain.tiles.Basemap
import java.io.File

/**
 * The app's single tile server, started once from [nz.mckenzie.sprayday.SprayDayApplication]
 * and shared by every map screen.
 *
 * A process-wide holder is a deliberate simplification, in the same spirit as
 * `TrackingState`: there is exactly one server and one store per source, every map must
 * use the same tile URL for the offline store to be worth anything, and threading
 * the port through every screen would add plumbing without adding safety. The
 * authoritative data still lives in Room and on disk.
 */
object TileServerHolder {

    /** Where downloaded aerial imagery goes: the offline feature's own directory. */
    const val TILES_DIR = "tiles"

    /** Where a browsed basemap's tiles are cached, e.g. `tiles-cache-osm`. */
    const val CACHE_DIR_PREFIX = "tiles-cache-"

    @Volatile
    private var server: LocalTileServer? = null

    @Volatile
    private var currentKey: String = ""

    /** The XYZ template for [basemap] to give the map, or null if the server is not up yet. */
    fun templateUrl(basemap: Basemap): String? =
        server?.takeIf { it.isRunning }?.tileUrlTemplate(basemap.id)

    /** Idempotent: safe to call from the application and from tests. */
    fun start(context: Context, initialKey: String = BuildConfig.LINZ_API_KEY) {
        if (server != null) return

        val appContext = context.applicationContext
        currentKey = initialKey

        val started = LocalTileServer(sources(appContext))
        started.start()
        server = started

        // Follow the key from settings for the life of the process.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            SettingsRepository(appContext).linzApiKey.collect { currentKey = it }
        }
    }

    /**
     * One source per basemap, so each is cached and named where its own licence wants it.
     *
     * Shared rather than built twice: the editor's server serves the tiles the app's own map has
     * already fetched, from the same stores on disk, which is what makes a block downloaded for a
     * trip with no reception draw on the desk as well. A second list would be a second store, and
     * the two would fill up with the same tiles.
     */
    fun sources(context: Context): List<TileSource> = Basemap.entries.map { basemap ->
        TileSource(
            id = basemap.id,
            store = store(context, basemap),
            suffix = basemap.tileSuffix,
            contentType = basemap.contentType,
            // Read per request, so a key pasted into Settings takes effect at once.
            upstream = {
                when (basemap) {
                    Basemap.LINZ_AERIAL ->
                        currentKey.takeIf { it.isNotBlank() }?.let { key -> LinzTileFetcher(key) }

                    Basemap.OPENSTREETMAP -> OsmTileFetcher()
                }
            }
        )
    }

    /**
     * The tile store for one basemap.
     *
     * The two live in different places on purpose, and it is the licence that decides it:
     *
     *  - **Aerial imagery** goes in the app's own files as it always has, because it is the thing
     *    offline areas are downloaded *into*: deliberate, kept until the operator clears it, and
     *    carried in a phone backup with everything else. The path is unchanged, so a season of
     *    downloaded imagery is not re-fetched by this feature existing.
     *  - **Any other basemap** is a cache of what has been looked at, so it goes in the cache
     *    directory: Android may reclaim it under storage pressure, and it is not backed up. What
     *    it must never be is *pre-fetched* - see [Basemap.prefetchable] - and nothing but the map
     *    itself ever asks for these tiles.
     */
    fun store(context: Context, basemap: Basemap): OfflineTileStore {
        val appContext = context.applicationContext
        val root = when (basemap) {
            Basemap.LINZ_AERIAL -> File(appContext.filesDir, TILES_DIR)
            Basemap.OPENSTREETMAP -> File(appContext.cacheDir, "$CACHE_DIR_PREFIX${basemap.id}")
        }
        return OfflineTileStore(root, basemap.tileSuffix)
    }

    /**
     * The store the offline imagery feature owns: the aerial one.
     *
     * Named rather than defaulted so that adding a basemap cannot quietly change what an offline
     * area downloads into.
     */
    fun imageryStore(context: Context): OfflineTileStore = store(context, Basemap.LINZ_AERIAL)

    internal fun stopForTest() {
        server?.stop()
        server = null
    }
}

