package nz.mckenzie.sprayday.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import nz.mckenzie.sprayday.domain.geo.GeoPoint

/** A stream of raw GPS fixes. Abstracted so the pipeline can be driven by a fake. */
interface LocationSource {
    /** Cold flow of fixes. Collection starts the updates; cancelling stops them. */
    fun updates(): Flow<GeoPoint>
}

/**
 * Fused location from Google Play services.
 *
 * Fused is battery-aware (it fuses GPS with the device's other sensors), which
 * matters for a recorder that runs for the length of a spray run. Accuracy is
 * still filtered downstream by [nz.mckenzie.sprayday.domain.geo.TrackPointFilter].
 */
class FusedLocationSource(
    context: Context,
    private val intervalMs: Long = 2_000L,
    private val minDistanceM: Float = 3f
) : LocationSource {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission") // Callers gate on the permission before recording.
    override fun updates(): Flow<GeoPoint> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(minDistanceM)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(
                        GeoPoint(
                            lat = location.latitude,
                            lng = location.longitude,
                            altitudeM = if (location.hasAltitude()) location.altitude else null,
                            accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                            speedMps = if (location.hasSpeed()) location.speed else null,
                            bearingDeg = if (location.hasBearing()) location.bearing else null,
                            timeMs = if (location.time > 0L) location.time else System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        // The Looper must be explicit: this flow is collected on a background
        // dispatcher with no Looper of its own, and passing null there throws
        // "invalid null looper" from inside Play services.
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }

        awaitClose { client.removeLocationUpdates(callback) }
    }
}
