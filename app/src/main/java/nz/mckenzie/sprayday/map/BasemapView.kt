package nz.mckenzie.sprayday.map

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds as DomainBounds
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.tracking.DevicePosition
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.roundToInt
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.tiles.Basemap

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

// The four asset layers are named in [AssetLayerIds], which is also where the map's own switches
// find them: the layer that is created and the layer a switch hides are the same name by
// construction, rather than two strings that have to be kept in step.

internal const val POSITION_SOURCE = "sprayday-position"
internal const val POSITION_ACCURACY_LAYER = "sprayday-position-accuracy"
internal const val POSITION_DOT_LAYER = "sprayday-position-dot"

/**
 * The uncertainty around the dot, faint enough to read the imagery through and to sit under
 * the work rather than over it.
 *
 * Alpha in `rgba()` rather than an eight-digit hex: every style version this app loads
 * understands the first, and the second is a newer thing to rely on.
 */
private const val POSITION_ACCURACY_FILL = "rgba(33, 33, 33, 0.16)"
private const val POSITION_ACCURACY_OUTLINE = "rgba(33, 33, 33, 0.38)"

/** Padding around the track network when the camera frames it. */
private const val BOUNDS_PADDING_PX = 96

/**
 * How long the camera takes to glide to the next fix while following.
 *
 * Fixes arrive every couple of seconds, so a glide of about a second reads as the map moving
 * along with the phone; a jump from fix to fix reads as the map twitching, and the operator
 * has both hands on a quad bike.
 */
private const val FOLLOW_DURATION_MS = 900

/**
 * The chosen basemap with the track network drawn on top.
 *
 * The native [MapView] is hosted directly rather than through a Compose map wrapper, so that the
 * app keeps control of the style document - which is what lets one tile server serve every
 * basemap, and lets a downloaded area work with no reception.
 */
@Composable
fun BasemapView(
    /**
     * Which map to draw under the work. Imagery unless the operator says otherwise; see
     * [nz.mckenzie.sprayday.domain.tiles.Basemap].
     */
    basemap: Basemap,
    /**
     * The LINZ key, for the basemaps that need one.
     *
     * Passed in rather than read here because a key that changes has to reload the style, and
     * that is the screen's flow to observe. A basemap that needs no key ignores it entirely,
     * which is the whole point of having one: the map still works the day a LINZ key expires.
     */
    apiKey: String,
    assetGeoJson: String,
    modifier: Modifier = Modifier,
    /**
     * Whether the camera should keep the phone in the middle of the screen.
     *
     * Set while a pass is being driven, when the map is being watched rather than read.
     * Following gives way the moment the operator drags the map - see [onFollowBroken] -
     * because a camera that swings back to the phone at every fix is a camera you cannot look
     * at a block with. The zoom is always the operator's: following moves the map, it does
     * not zoom it.
     */
    follow: Boolean = false,
    /**
     * The operator dragged the map, which is them saying "let me look at this instead".
     *
     * The screen turns following off and says so, rather than the map fighting the drag on
     * the next fix.
     */
    onFollowBroken: () -> Unit = {},
    fitBounds: DomainBounds? = null,
    /**
     * The layers of the work this map is not drawing.
     *
     * Empty by default, which is what every map that is not the operator's own home map wants: a
     * screen that is *about* something - one asset's own line, a pass being recorded, a spot being
     * drawn - has to show it whatever the switches on the home map say. Hiding a layer is a
     * property of the style's layers rather than a reload, so the camera, the tiles and the zoom
     * stay exactly where the operator left them.
     */
    hiddenLayers: Set<AssetLayer> = emptySet(),
    /**
     * A camera move the operator asked for. The bounds say where; [recentreCount] is what says
     * it is a new request rather than the same one arriving twice, so the second tap on
     * "where am I" moves the map again.
     */
    recentreBounds: DomainBounds? = null,
    recentreCount: Int = 0,
    /**
     * Tile template to render. Defaults to the app's own tile server for the chosen basemap, so
     * every map shares one tile path - and therefore one cache, one set of offline tiles - rather
     * than some screens talking to a provider directly and some not.
     */
    tileUrlTemplate: String? = TileServerHolder.templateUrl(basemap),
    onMapClick: ((latitude: Double, longitude: Double, radiusM: Double) -> Unit)? = null,
    initialTarget: LatLng = DEFAULT_CAMERA_TARGET,
    initialZoom: Double = DEFAULT_CAMERA_ZOOM
) {
    val mapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleState = remember { mutableStateOf<Style?>(null) }
    val mapViewState = remember { mutableStateOf<MapView?>(null) }
    var boundsApplied by remember { mutableStateOf(false) }

    // The houses a place is drawn with are rendered at the device's own density, so a place is
    // the size it is meant to be, and sharp, on the screen it lands on. Read here and passed
    // in because a style is loaded from two places - the first frame, and a basemap change -
    // and both want the same answer.
    val density = LocalDensity.current.density

    // Keeps the tap handler current without rebuilding the map.
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnFollowBroken by rememberUpdatedState(onFollowBroken)

    // Where the phone is. Every map in the app shows it, and this is the whole of how: the
    // marker is built here from the app's own stream rather than handed in per screen, so a
    // screen added later cannot be the one that forgot. Collection lasts exactly as long as
    // this map is on screen.
    //
    // The stream is remembered rather than asked for on the way through: a fresh one per
    // recomposition would start from "no fix yet" every time the map drew, and a marker that
    // blinks out while the camera is moving is a marker nobody ever sees.
    val positionScope = rememberCoroutineScope()
    val fixes = remember(positionScope) { DevicePosition.updates(positionScope) }
    val fix by fixes.collectAsStateWithLifecycle()
    val positionGeoJson = remember(fix) { PositionGeoJson.build(fix) }

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
                    // A drag is the operator asking for the map rather than the phone, so
                    // following gives way to them. Only a gesture counts: the map's own
                    // camera moves arrive here too, and treating those as a drag would stop
                    // following the first time it followed anything.
                    map.addOnCameraMoveStartedListener(
                        object : MapLibreMap.OnCameraMoveStartedListener {
                            override fun onCameraMoveStarted(reason: Int) {
                                if (reason ==
                                    MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                                ) {
                                    currentOnFollowBroken()
                                }
                            }
                        }
                    )
                    map.loadSprayDayStyle(
                        basemap = basemap,
                        apiKey = apiKey,
                        tileUrlTemplate = tileUrlTemplate,
                        assetGeoJson = assetGeoJson,
                        positionGeoJson = positionGeoJson,
                        density = density,
                        hiddenLayers = hiddenLayers
                    ) { style ->
                        styleState.value = style
                    }
                }
                mapViewState.value = this
            }
        }
    )

    // The key itself is not used here: the style URL already embeds it, or the tile
    // server holds it. Note what is *not* a key: the position, which changes every few
    // seconds and must never send the map back to reload its style.
    LaunchedEffect(basemap, apiKey, tileUrlTemplate) {
        val map = mapState.value
        val usable = !tileUrlTemplate.isNullOrBlank() || !basemap.needsKey || apiKey.isNotBlank()
        if (map != null && usable) {
            map.loadSprayDayStyle(
                basemap = basemap,
                apiKey = apiKey,
                tileUrlTemplate = tileUrlTemplate,
                assetGeoJson = assetGeoJson,
                positionGeoJson = positionGeoJson,
                density = density,
                hiddenLayers = hiddenLayers
            ) { style ->
                styleState.value = style
            }
        }
    }

    LaunchedEffect(assetGeoJson, styleState.value) {
        styleState.value?.getSourceAs<GeoJsonSource>(ASSETS_SOURCE)?.setGeoJson(assetGeoJson)
    }

    // A layer the operator has switched off, applied to the style that is already loaded. This is a
    // property of a layer rather than a new style, so the camera, the tiles and the zoom are all
    // left exactly where they were - which is what makes the switch usable while reading the map
    // rather than something that sends you back to the farm gate every time you touch it.
    LaunchedEffect(hiddenLayers, styleState.value) {
        val style = styleState.value ?: return@LaunchedEffect
        applyLayerVisibility(style, hiddenLayers)
    }

    // The marker follows the fixes. Setting the GeoJSON on one source rather than rebuilding
    // the layer is what keeps a moving dot cheap: the style is written once, and a fix only
    // replaces two features.
    LaunchedEffect(positionGeoJson, styleState.value) {
        styleState.value?.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(positionGeoJson)
    }

    // Following: the phone stays in the middle, at the zoom the operator chose, while the pass
    // is being driven. Every fix glides the camera and a newer fix replaces the glide in
    // flight, so the map moves with the phone rather than jumping from fix to fix. Turning
    // following on moves the map at once, rather than waiting for the next fix to arrive.
    LaunchedEffect(fix, follow, mapState.value) {
        val map = mapState.value ?: return@LaunchedEffect
        val point = fix ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect

        map.animateCamera(
            CameraUpdateFactory.newLatLng(LatLng(point.lat, point.lng)),
            FOLLOW_DURATION_MS
        )
        // Following settles the question of the first frame in the same way an asked-for move
        // does: an operator who starts recording before the tracks' bounds come back off the
        // database must not be dragged off their own pass a second later.
        boundsApplied = true
    }

    // A recentre asked for by the operator. The map reads the work more than it reads the
    // phone, so it stays where it was put unless it is following a pass or is asked to move.
    LaunchedEffect(recentreBounds, recentreCount, mapState.value) {
        val map = mapState.value ?: return@LaunchedEffect
        val bounds = recentreBounds ?: return@LaunchedEffect

        map.animateCamera(cameraFor(bounds))
        // An asked-for camera move settles the question of the first frame. Without this, an
        // operator quick enough to tap "where am I" before the tracks' bounds come back off the
        // database is dragged from their own paddock to the work a few seconds later, having
        // done nothing wrong.
        boundsApplied = true
    }

    // Frame the track network the first time we know where it is, so opening the
    // app shows "my tracks", not an arbitrary patch of countryside.
    LaunchedEffect(fitBounds, mapState.value) {
        val map = mapState.value ?: return@LaunchedEffect
        val bounds = fitBounds ?: return@LaunchedEffect
        if (boundsApplied) return@LaunchedEffect

        map.animateCamera(cameraFor(bounds))
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
 * colour, and a place is drawn as a house in the same one.
 *
 * [density] is the device's, and it is what the houses are rendered at: see
 * [addPlaceIconImages].
 */
internal fun MapLibreMap.loadSprayDayStyle(
    basemap: Basemap,
    apiKey: String,
    tileUrlTemplate: String?,
    assetGeoJson: String,
    positionGeoJson: String,
    density: Float,
    hiddenLayers: Set<AssetLayer>,
    onLoaded: (Style) -> Unit
) {
    val json = when {
        // A running tile server is the preferred source: it serves downloaded areas
        // with no reception, keeps whatever it fetches, and holds the LINZ key so the
        // style does not have to.
        !tileUrlTemplate.isNullOrBlank() -> BasemapStyles.rasterStyleJson(basemap, tileUrlTemplate)

        // No server (tests, previews). A basemap that needs no key can simply be talked to.
        !basemap.needsKey -> BasemapStyles.rasterStyleJson(basemap, basemap.tileTemplate(""))

        apiKey.isNotBlank() -> BasemapStyles.rasterStyleJson(basemap, basemap.tileTemplate(apiKey))

        // A key is needed and there is none: a calm placeholder rather than thousands of
        // tiles that will come back HTTP 400.
        else -> BasemapStyles.blankStyleJson()
    }

    setStyle(Style.Builder().fromJson(json)) { style ->
        if (style.getSource(ASSETS_SOURCE) == null) {
            style.addSource(GeoJsonSource(ASSETS_SOURCE, assetGeoJson))
        }
        // One line layer per kind, because line-dasharray is a constant in MapLibre
        // rather than something a feature can carry. So a track is solid, a road is
        // dashed and infrastructure is dotted, and the filter is what keeps them apart.
        addLineLayer(style, AssetLayerIds.TRACKS, AssetKind.TRACK)
        addLineLayer(style, AssetLayerIds.ROADS, AssetKind.ROAD)
        addLineLayer(style, AssetLayerIds.FENCELINES, AssetKind.FENCELINE)
        // A place is a house, not a short line: drawing a picnic table as a line would
        // claim a shape the record does not have, and a house says what the thing is
        // rather than only where it is - the same picture the asset's row carries.
        addPlaceIconImages(style, density)
        if (style.getLayer(AssetLayerIds.PLACES) == null) {
            style.addLayer(
                SymbolLayer(AssetLayerIds.PLACES, ASSETS_SOURCE)
                    .withProperties(
                        // The feature names the house it wants, so which colour a place is
                        // drawn in is decided in Kotlin, where it is tested - and the
                        // fallback means a place can never be drawn as nothing at all.
                        PropertyFactory.iconImage(
                            Expression.coalesce(
                                Expression.get(AssetGeoJson.ICON_PROPERTY),
                                Expression.literal(PlaceIcons.FALLBACK_IMAGE_NAME)
                            )
                        ),
                        // The picture is already the size a place is drawn at on this device -
                        // see [addPlaceIconImages] - because an image is drawn at its own
                        // pixels: there is no icon-size here to keep in step with the screen.
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                        // Every place is drawn, wherever it is. A dot never hid from another
                        // dot, and a spot that vanishes because a second one is near it reads
                        // as a spot that has been deleted.
                        PropertyFactory.iconAllowOverlap(Expression.literal(true)),
                        PropertyFactory.iconIgnorePlacement(Expression.literal(true))
                    )
                    .withFilter(shapeIs(AssetShape.POINT))
            )
        }

        // Where the phone is. Added after the assets so it draws over them: the work is what
        // the map is for, and where you are standing is read on top of it rather than under it.
        if (style.getSource(POSITION_SOURCE) == null) {
            style.addSource(GeoJsonSource(POSITION_SOURCE, positionGeoJson))
        }
        if (style.getLayer(POSITION_ACCURACY_LAYER) == null) {
            style.addLayer(
                FillLayer(POSITION_ACCURACY_LAYER, POSITION_SOURCE)
                    .withProperties(
                        PropertyFactory.fillColor(POSITION_ACCURACY_FILL),
                        // Faint, but the edge is what the eye measures against the lines
                        // around it, so the edge is the less faint of the two.
                        PropertyFactory.fillOutlineColor(POSITION_ACCURACY_OUTLINE)
                    )
                    .withFilter(partIs(PositionGeoJson.PART_ACCURACY))
            )
        }
        if (style.getLayer(POSITION_DOT_LAYER) == null) {
            style.addLayer(
                CircleLayer(POSITION_DOT_LAYER, POSITION_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor(Expression.literal(AssetColors.POSITION)),
                        PropertyFactory.circleRadius(Expression.literal(7f)),
                        // The white ring the asset dots wear, for the same reason: a dark dot
                        // over dark imagery needs one, and this is the darkest thing on the map.
                        PropertyFactory.circleStrokeColor(Expression.literal("#FFFFFF")),
                        PropertyFactory.circleStrokeWidth(Expression.literal(2.5f))
                    )
                    .withFilter(partIs(PositionGeoJson.PART_DOT))
            )
        }
        // The switches, applied to the style that has just been built, so that the first frame is
        // already the map the operator asked for rather than one that flashes everything and then
        // hides half of it.
        applyLayerVisibility(style, hiddenLayers)
        onLoaded(style)
    }
}

/**
 * Draws or hides each layer of the work, as the switches have it.
 *
 * Visibility rather than removing and re-adding the layers: MapLibre keeps them, so a layer that
 * has been switched off costs nothing to switch back on, and the source is left alone - which is
 * the difference between this and reloading the style.
 *
 * Called as the style is built and again whenever the choice changes. A layer that is not in the
 * style is skipped: this is the map's own list, and a style that is missing one of them is a
 * reason to draw the rest rather than to stop.
 */
private fun applyLayerVisibility(style: Style, hidden: Set<AssetLayer>) {
    AssetLayer.ALL.forEach { layer ->
        val target = style.getLayer(AssetLayerIds.of(layer)) ?: return@forEach
        target.setProperties(
            PropertyFactory.visibility(if (layer in hidden) Property.NONE else Property.VISIBLE)
        )
    }
}

/**
 * Hands the style the houses a place is drawn with: one per colour a traffic light can be.
 *
 * Drawn rather than shipped as a sprite, for the same reason the asset list's glyphs are:
 * four small pictures that only mean anything as a set are easier to keep honest in one
 * place than across a sprite sheet and the pixel ratios that come with it.
 *
 * Each is rendered at the device's own density - one image pixel per screen pixel - which is
 * what draws a place at [PlaceIcons.HOUSE_DP] on whatever screen it lands on, and keeps the
 * roof and the white edge sharp while it does it. There is no size in the style to get wrong
 * and nothing for the map to scale.
 */
private fun addPlaceIconImages(style: Style, density: Float) {
    val sizePx = (PlaceIcons.HOUSE_DP * density).roundToInt()
    if (sizePx <= 0) return

    val missing = HashMap<String, Bitmap>(PlaceIcons.COLORS.size)
    PlaceIcons.COLORS.forEach { colorHex ->
        val name = PlaceIcons.houseImageName(colorHex)
        // An image belongs to the style it was added to, and a style can be loaded again -
        // the same basemap with a key that has just been entered, say. Adding one twice is
        // not wrong so much as unnecessary work on the main thread.
        if (style.getImage(name) == null) {
            missing[name] = HouseMarker.bitmap(
                colorHex = colorHex,
                sizePx = sizePx,
                outlinePx = PlaceIcons.HOUSE_OUTLINE_DP * density
            )
        }
    }
    if (missing.isNotEmpty()) style.addImages(missing)
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

/** Which of the marker's two features a layer draws: the dot, or the ring of uncertainty. */
private fun partIs(part: String): Expression =
    Expression.eq(Expression.get(PositionGeoJson.PART_PROPERTY), Expression.literal(part))

/**
 * The camera that fits a set of bounds, with the app's padding.
 *
 * One place rather than three, because three things now ask for exactly this: the first frame
 * on the work, the first frame on the phone when there is no work, and the recentre the
 * operator asks for by tapping.
 */
private fun cameraFor(bounds: DomainBounds): CameraUpdate =
    CameraUpdateFactory.newLatLngBounds(
        org.maplibre.android.geometry.LatLngBounds.Builder()
            .include(LatLng(bounds.minLat, bounds.minLng))
            .include(LatLng(bounds.maxLat, bounds.maxLng))
            .build(),
        BOUNDS_PADDING_PX
    )
