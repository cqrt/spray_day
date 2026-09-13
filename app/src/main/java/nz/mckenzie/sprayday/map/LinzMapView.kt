package nz.mckenzie.sprayday.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds as DomainBounds
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** Rural Marlborough (Wairau plains) - a sensible land start, not open sea. */
val DEFAULT_CAMERA_TARGET = LatLng(-41.51, 173.96)
const val DEFAULT_CAMERA_ZOOM = 11.0

internal const val TRACKS_SOURCE = "sprayday-tracks"
internal const val TRACKS_LAYER = "sprayday-tracks-line"

/** Padding around the track network when the camera frames it. */
private const val BOUNDS_PADDING_PX = 96

/**
 * The LINZ aerial basemap with the track network drawn on top.
 *
 * The native [MapView] is hosted directly (rather than through a Compose map
 * wrapper) so that MapLibre's OfflineManager stays available for the offline
 * region downloads.
 */
@Composable
fun LinzMapView(
    apiKey: String,
    trackGeoJson: String,
    modifier: Modifier = Modifier,
    fitBounds: DomainBounds? = null,
    initialTarget: LatLng = DEFAULT_CAMERA_TARGET,
    initialZoom: Double = DEFAULT_CAMERA_ZOOM
) {
    val mapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleState = remember { mutableStateOf<Style?>(null) }
    val mapViewState = remember { mutableStateOf<MapView?>(null) }
    var boundsApplied by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).apply {
                onCreate(null)
                onStart()
                onResume()
                getMapAsync { map ->
                    mapState.value = map
                    map.uiSettings.isCompassEnabled = true
                    // The licence requires attribution to be visible; MapLibre's own
                    // control is backed up by an always-visible overlay in MapScreen.
                    map.uiSettings.isAttributionEnabled = true
                    map.cameraPosition = CameraPosition.Builder()
                        .target(initialTarget)
                        .zoom(initialZoom)
                        .build()
                    map.loadSprayDayStyle(apiKey, trackGeoJson) { style -> styleState.value = style }
                }
                mapViewState.value = this
            }
        }
    )

    // The key itself is not used here: the style URL already embeds it.
    LaunchedEffect(apiKey) {
        val map = mapState.value
        if (map != null && apiKey.isNotBlank()) {
            map.loadSprayDayStyle(apiKey, trackGeoJson) { style -> styleState.value = style }
        }
    }

    LaunchedEffect(trackGeoJson, styleState.value) {
        styleState.value?.getSourceAs<GeoJsonSource>(TRACKS_SOURCE)?.setGeoJson(trackGeoJson)
    }

    // Frame the track network the first time we know where it is, so opening the
    // app shows "my tracks", not an arbitrary patch of countryside.
    LaunchedEffect(fitBounds, mapState.value) {
        val map = mapState.value ?: return@LaunchedEffect
        val bounds = fitBounds ?: return@LaunchedEffect
        if (boundsApplied) return@LaunchedEffect

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(
                org.maplibre.android.geometry.LatLngBounds.Builder()
                    .include(LatLng(bounds.minLat, bounds.minLng))
                    .include(LatLng(bounds.maxLat, bounds.maxLng))
                    .build(),
                BOUNDS_PADDING_PX
            )
        )
        boundsApplied = true
    }

    // Hand the map view back to MapLibre when this screen leaves the composition.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val mapView = mapViewState.value ?: return@onDispose
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            mapViewState.value = null
            mapState.value = null
            styleState.value = null
        }
    }
}

/**
 * Loads a spray-day style and installs the track source/layer. Tracks are drawn
 * from a data-driven `stroke` property so each track takes its traffic-light
 * colour.
 */
internal fun MapLibreMap.loadSprayDayStyle(
    apiKey: String,
    trackGeoJson: String,
    onLoaded: (Style) -> Unit
) {
    val json = if (apiKey.isBlank()) {
        LinzBasemap.blankStyleJson()
    } else {
        LinzBasemap.aerialStyleJson(apiKey)
    }

    setStyle(Style.Builder().fromJson(json)) { style ->
        if (style.getSource(TRACKS_SOURCE) == null) {
            style.addSource(GeoJsonSource(TRACKS_SOURCE, trackGeoJson))
        }
        if (style.getLayer(TRACKS_LAYER) == null) {
            style.addLayer(
                LineLayer(TRACKS_LAYER, TRACKS_SOURCE).withProperties(
                    PropertyFactory.lineColor(Expression.get("stroke")),
                    PropertyFactory.lineWidth(Expression.literal(5f)),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineOpacity(Expression.literal(0.9f))
                )
            )
        }
        onLoaded(style)
    }
}
