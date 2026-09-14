package nz.mckenzie.sprayday

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.reminders.DueReminderScheduler
import org.maplibre.android.MapLibre

class SprayDayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is instantiated. MapLibre needs no API key
        // of its own - the LINZ Basemaps key is supplied per style.
        MapLibre.getInstance(this)

        // MapLibre gates its HTTP requests on the device's connectivity, so with
        // no reception it would not request anything at all - including from the
        // app's own loopback tile server, which is exactly what holds the
        // downloaded imagery. Telling it "connected" makes it ask; the server
        // answers from disk, and simply 404s for tiles it never stored.
        MapLibre.setConnected(true)

        // One tile server for the whole app: the map reads tiles from it whether
        // or not there is a network, and anything browsed online is kept for
        // offline use.
        TileServerHolder.start(this)

        // Keep the reminder schedule in step with the setting, whatever changes it -
        // the settings switch, or a restore putting the setting back.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            SettingsRepository(this@SprayDayApplication).remindersEnabled.collect { enabled ->
                DueReminderScheduler.sync(this@SprayDayApplication, enabled)
            }
        }
    }
}
