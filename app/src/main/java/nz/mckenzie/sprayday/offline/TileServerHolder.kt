package nz.mckenzie.sprayday.offline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.SettingsRepository
import java.io.File

/**
 * The app's single tile server, started once from [nz.mckenzie.sprayday.SprayDayApplication]
 * and shared by every map screen.
 *
 * A process-wide holder is a deliberate simplification, in the same spirit as
 * `TrackingState`: there is exactly one server and one tile store, every map must
 * use the same tile URL for the offline store to be worth anything, and threading
 * the port through every screen would add plumbing without adding safety. The
 * authoritative data still lives in Room and on disk.
 */
object TileServerHolder {

    const val TILES_DIR = "tiles"

    @Volatile
    private var server: LocalTileServer? = null

    @Volatile
    private var currentKey: String = ""

    /** The XYZ template to give the map, or null if the server is not up yet. */
    val templateUrl: String?
        get() = server?.takeIf { it.isRunning }?.tileUrlTemplate()

    /** Idempotent: safe to call from the application and from tests. */
    fun start(context: Context, initialKey: String = BuildConfig.LINZ_API_KEY) {
        if (server != null) return

        val appContext = context.applicationContext
        currentKey = initialKey

        val started = LocalTileServer(
            store = store(appContext),
            // Read per request, so a key pasted into Settings takes effect at once.
            upstreamProvider = {
                currentKey.takeIf { it.isNotBlank() }?.let { key -> LinzTileFetcher(key) }
            }
        )
        started.start()
        server = started

        // Follow the key from settings for the life of the process.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            SettingsRepository(appContext).linzApiKey.collect { currentKey = it }
        }
    }

    /** The tile store every part of the app shares. */
    fun store(context: Context): OfflineTileStore =
        OfflineTileStore(File(context.applicationContext.filesDir, TILES_DIR))

    internal fun stopForTest() {
        server?.stop()
        server = null
    }
}
