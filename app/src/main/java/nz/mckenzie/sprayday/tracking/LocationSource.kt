package nz.mckenzie.sprayday.tracking

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import kotlin.coroutines.resume

/** A stream of raw GPS fixes. Abstracted so the pipeline can be driven by a fake. */
interface LocationSource {
    /** Cold flow of fixes. Collection starts the updates; cancelling stops them. */
    fun updates(): Flow<GeoPoint>

    /**
     * A single fix, for centring the map when there is nothing else to go on.
     *
     * Null when the permission is missing or no provider answers in time, so callers
     * fall back to a neutral view rather than waiting.
     */
    suspend fun currentLocation(): GeoPoint?
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

    private val appContext: Context = context.applicationContext

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One fix, for deciding where to put the camera.
     *
     * A *fresh* fix first, then the last known position: a cached fix can be hundreds
     * of kilometres and hours old, which is the same "why is my map somewhere else?"
     * problem as a hard-coded default. If no provider answers in time, a stale
     * position still beats a country-wide view.
     */
    override suspend fun currentLocation(): GeoPoint? {
        if (!hasPermission()) return null
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { freshLocation() }
            ?: lastKnownLocation()
    }

    @SuppressLint("MissingPermission") // Checked above; callers also gate on it.
    private suspend fun freshLocation(): GeoPoint? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellation.cancel() }
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
            .addOnSuccessListener { location -> continuation.resume(location?.toGeoPoint()) }
            .addOnFailureListener { continuation.resume(null) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): GeoPoint? = suspendCancellableCoroutine { continuation ->
        client.lastLocation
            .addOnSuccessListener { location -> continuation.resume(location?.toGeoPoint()) }
            .addOnFailureListener { continuation.resume(null) }
    }

    @SuppressLint("MissingPermission") // Callers gate on the permission before recording.
    override fun updates(): Flow<GeoPoint> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(minDistanceM)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location -> trySend(location.toGeoPoint()) }
            }
        }

        // The Looper must be explicit: this flow is collected on a background
        // dispatcher with no Looper of its own, and passing null there throws
        // "invalid null looper" from inside Play services.
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private companion object {
        /** Long enough for a high-accuracy fix, short enough not to hold the map up. */
        const val FRESH_FIX_TIMEOUT_MS = 4_000L
    }
}

/** One mapping, so a fix means the same thing whether streamed or asked for once. */
private fun Location.toGeoPoint() = GeoPoint(
    lat = latitude,
    lng = longitude,
    altitudeM = if (hasAltitude()) altitude else null,
    accuracyM = if (hasAccuracy()) accuracy else null,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    timeMs = if (time > 0L) time else System.currentTimeMillis()
)
