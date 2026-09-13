package nz.mckenzie.sprayday

import android.app.Application
import org.maplibre.android.MapLibre

class SprayDayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is instantiated. MapLibre needs no API key
        // of its own - the LINZ Basemaps key is supplied per style.
        MapLibre.getInstance(this)
    }
}
