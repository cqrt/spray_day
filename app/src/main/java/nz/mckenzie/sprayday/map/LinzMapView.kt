package nz.mckenzie.sprayday.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds as DomainBounds
import nz.mckenzie.sprayday.offline.TileServerHolder
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.sources.GeoJsonSource
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape

/**
 * Where the map opens when there is nothing better to show: the whole country, at a
 * zoom that shows both islands.
 *
 * It used to be a farm near Blenheim, which was a reasonable guess for exactly one
 * person - an operator in Invercargill opened the app looking at Marlborough. The
 * map prefers the device's own position (see `MapViewModel.startBounds`) and the
 * operator's own tracks, and only falls back to this.
 */
val DEFAULT_CAMERA_TARGET = LatLng(-41.5, 172.8)
const val DEFAULT_CAMERA_ZOOM = 6.0

internal const val ASSETS_SOURCE = "sprayday-assets"
internal const val ASSETS_LAYER = "sprayday-assets-line-track"
internal const val ASSETS_ROAD_LAYER = "sprayday-assets-line-road"
internal const val ASSETS_INFRASTRUCTURE_LAYER = "sprayday-assets-line-infrastructure"
internal const val ASSETS_POINT_LAYER = "sprayday-assets-point"

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
    assetGeoJson: String,
    modifier: Modifier = Modifier,
    fitBounds: DomainBounds? = null,
    /**
     * Tile template to render. Defaults to the app's own tile server so every map
     * shares one tile path - and therefore one offline store - rather than some
     * screens talking to LINZ directly and some not.
     */
    tileUrlTemplate: String? = TileServerHolder.templateUrl,
    onMapClick: ((latitude: Double, longitude: Double, radiusM: Double) -> Unit)? = null,
    initialTarget: LatLng = DEFAULT_CAMERA_TARGET,
    initialZoom: Double = DEFAULT_CAMERA_ZOOM
) {
    val mapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleState = remember { mutableStateOf<Style?>(null) }
    val mapViewState = remember { mutableStateOf<MapView?>(null) }
    var boundsApplied by remember { mutableStateOf(false) }

    // Keeps the tap handler current without rebuilding the map.
    val currentOnMapClick by rememberUpdatedState(onMapClick)

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
                    // North stays up. A gloved hand rotates this map by accident, and the
                    // mistake is invisible until a fenceline points the wrong way - while
                    // nothing on it is easier to read at an angle.
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.uiSettings.isTiltGesturesEnabled = false
                    map.cameraPosition = CameraPosition.Builder()
                        .target(initialTarget)
                        .zoom(initialZoom)
                        .build()
                    map.addOnMapClickListener { latLng ->
                        val handler = currentOnMapClick
                        if (handler == null) {
                            false
                        } else {
                            // The radius a fingertip covers at this zoom, so a track is
                            // tappable whether the map is showing a paddock or an island.
                            handler(
                                latLng.latitude,
                                latLng.longitude,
                                AssetHitTest.toleranceForZoom(map.cameraPosition.zoom, latLng.latitude)
                            )
                            true
                        }
                    }
                    map.loadSprayDayStyle(apiKey, tileUrlTemplate, assetGeoJson) { style ->
                        styleState.value = style
                    }
                }
                mapViewState.value = this
            }
        }
    )

    // The key itself is not used here: the style URL already embeds it, or the
    // tile server holds it.
    LaunchedEffect(apiKey, tileUrlTemplate) {
        val map = mapState.value
        if (map != null && (apiKey.isNotBlank() || !tileUrlTemplate.isNullOrBlank())) {
            map.loadSprayDayStyle(apiKey, tileUrlTemplate, assetGeoJson) { style ->
                styleState.value = style
            }
        }
    }

    LaunchedEffect(assetGeoJson, styleState.value) {
        styleState.value?.getSourceAs<GeoJsonSource>(ASSETS_SOURCE)?.setGeoJson(assetGeoJson)
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
    tileUrlTemplate: String?,
    assetGeoJson: String,
    onLoaded: (Style) -> Unit
) {
    val json = when {
        // A running tile server is the preferred source: it serves downloaded
        // areas with no reception and keeps whatever it fetches, and it holds the
        // API key so the style does not have to.
        !tileUrlTemplate.isNullOrBlank() -> LinzBasemap.aerialStyleJsonForTemplate(tileUrlTemplate)

        // No server (tests, previews): talk to LINZ directly.
        apiKey.isNotBlank() -> LinzBasemap.aerialStyleJson(apiKey)

        else -> LinzBasemap.blankStyleJson()
    }

    setStyle(Style.Builder().fromJson(json)) { style ->
        if (style.getSource(ASSETS_SOURCE) == null) {
            style.addSource(GeoJsonSource(ASSETS_SOURCE, assetGeoJson))
        }
        // One line layer per kind, because line-dasharray is a constant in MapLibre
        // rather than something a feature can carry. So a track is solid, a road is
        // dashed and infrastructure is dotted, and the filter is what keeps them apart.
        addLineLayer(style, ASSETS_LAYER, AssetKind.TRACK)
        addLineLayer(style, ASSETS_ROAD_LAYER, AssetKind.ROAD)
        addLineLayer(style, ASSETS_INFRASTRUCTURE_LAYER, AssetKind.INFRASTRUCTURE)
        // A place is a circle, not a short line: drawing a picnic table as a line would
        // claim a shape the record does not have.
        if (style.getLayer(ASSETS_POINT_LAYER) == null) {
            style.addLayer(
                CircleLayer(ASSETS_POINT_LAYER, ASSETS_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor(Expression.get("stroke")),
                        PropertyFactory.circleRadius(Expression.literal(8f)),
                        // A white ring keeps a red or amber dot readable over dark imagery.
                        PropertyFactory.circleStrokeColor(Expression.literal("#FFFFFF")),
                        PropertyFactory.circleStrokeWidth(Expression.literal(2f))
                    )
                    .withFilter(shapeIs(AssetShape.POINT))
            )
        }
        onLoaded(style)
    }
}

/**
 * Adds one of the kind's line layers if it is not already there.
 *
 * The dash pattern comes from [AssetLineStyles], so which kind is drawn dashed is
 * decided in one testable place rather than here.
 */
private fun addLineLayer(style: Style, id: String, kind: AssetKind) {
    if (style.getLayer(id) != null) return

    val properties = mutableListOf<PropertyValue<*>>(
        PropertyFactory.lineColor(Expression.get("stroke")),
        PropertyFactory.lineWidth(Expression.literal(5f)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(Expression.literal(0.9f))
    )
    AssetLineStyles.forKind(kind)?.let { dash -> properties += PropertyFactory.lineDasharray(dash) }

    style.addLayer(
        LineLayer(id, ASSETS_SOURCE)
            .withProperties(*properties.toTypedArray())
            .withFilter(
                Expression.all(
                    Expression.eq(Expression.get("kind"), Expression.literal(kind.name)),
                    shapeIs(AssetShape.LINE)
                )
            )
    )
}

private fun shapeIs(shape: AssetShape): Expression =
    Expression.eq(Expression.get("shape"), Expression.literal(shape.name))
