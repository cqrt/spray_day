package nz.mckenzie.sprayday

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.backup.BackupScheduler
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.reminders.DueReminderScheduler
import nz.mckenzie.sprayday.update.UpdateCheckScheduler
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
        val settings = SettingsRepository(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.remindersEnabled.collect { enabled ->
                DueReminderScheduler.sync(this@SprayDayApplication, enabled)
            }
        }

        // The same for the update check: the switch that turns it off has to actually
        // stop the job that does it, or the setting is a lie.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.updateChecksEnabled.collect { enabled ->
                UpdateCheckScheduler.sync(this@SprayDayApplication, enabled)
            }
        }

        // And for the off-site copy, where the setting can arrive from a restore as well as
        // from the switch - a phone restored from a backup should start backing itself up.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settings.backUpAutomatically.collect { enabled ->
                BackupScheduler.sync(this@SprayDayApplication, enabled)
            }
        }
    }
}
