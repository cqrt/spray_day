package nz.mckenzie.sprayday.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.R
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.map.BasemapView
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.viewmodel.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenTab: (Tab) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAsset: (Long) -> Unit = {}
) {
    val apiKey by viewModel.linzApiKey.collectAsStateWithLifecycle()
    val basemap by viewModel.basemap.collectAsStateWithLifecycle()
    val tracks by viewModel.assetsWithDue.collectAsStateWithLifecycle()
    val geoJson by viewModel.assetGeoJson.collectAsStateWithLifecycle()
    val initialFrame by viewModel.initialFrame.collectAsStateWithLifecycle()
    val recentre by viewModel.recentre.collectAsStateWithLifecycle()
    val locationNotice by viewModel.locationNotice.collectAsStateWithLifecycle()
    val hiddenLayers by viewModel.hiddenLayers.collectAsStateWithLifecycle()

    var layersOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Where the app asks for the permission the marker needs, and asked on a tap rather than on
    // the way in: a prompt that arrives with the map is a prompt for nothing, and one that
    // arrived from the record screen already has an answer.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.recentreOnDevice() else viewModel.onLocationRefused()
    }

    val locate = {
        val allowed =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (allowed) {
            viewModel.recentreOnDevice()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                // Settings is the map's own key and switches. What used to be words up here -
                // Assets, Offline - are tabs in the bar below now, which is where a place belongs;
                // and the key is set once, so the icon is enough.
                actions = {
                    IconButton(onClick = { layersOpen = true }) {
                        AppIcon(IconGlyph.LAYERS, contentDescription = "What the map draws")
                    }
                    IconButton(onClick = onOpenSettings) {
                        AppIcon(IconGlyph.SETTINGS, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = { SprayDayNavBar(current = Tab.MAP, onSelect = onOpenTab) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            BasemapView(
                basemap = basemap,
                apiKey = apiKey,
                assetGeoJson = geoJson,
                // What the operator has switched off. The home map is the one these apply to: a
                // screen that is about one asset, or about the spot being drawn, draws it
                // whatever is hidden here.
                hiddenLayers = hiddenLayers,
                // The marker for the phone is the map's own business now, so this screen
                // says nothing about it: it draws where you are wherever a map is drawn.
                // The operator's own tracks first; failing that, where the device is;
                // failing that, the neutral country-wide default.
                fitBounds = initialFrame,
                // Where "show me" last asked the camera to be. Null until they ask: the map
                // does not follow the phone, because it is also for reading the work.
                recentreBounds = recentre?.bounds,
                recentreCount = recentre?.count ?: 0,
                // Tapping a track opens it: the map is where they are looking when they
                // wonder about a block. The radius comes from the map's zoom, so the tap
                // works at country scale as well as at spray height.
                onMapClick = { lat, lng, radiusM -> viewModel.assetAt(lat, lng, radiusM)?.let(onOpenAsset) },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DueLegend(tracks = tracks)
                locationNotice?.let { notice ->
                    LocationNoticeCard(
                        message = notice,
                        onDismiss = viewModel::clearLocationNotice
                    )
                }
            }

            // Where the app asks for the permission the marker needs, and asked on a tap: a
            // prompt that arrives with the map is a prompt for nothing, and one that arrived
            // from the record screen has an answer already.
            FilledTonalIconButton(
                onClick = locate,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                AppIcon(IconGlyph.LOCATE, contentDescription = "Show where I am")
            }

            // Only when the chosen basemap needs a key and none is set. OpenStreetMap needs none,
            // which is the whole reason it is in the app: the day a LINZ key expires, the map is
            // still a map.
            if (basemap.needsKey && apiKey.isBlank()) {
                MissingKeyCard(
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }

            AttributionStrip(
                basemap = basemap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 6.dp)
            )
        }
    }

    if (layersOpen) {
        LayersSheet(
            hidden = hiddenLayers,
            onHide = viewModel::setLayerHidden,
            onShowAll = viewModel::showEveryLayer,
            onDismiss = { layersOpen = false }
        )
    }
}

/**
 * What the map draws, and the switches for it.
 *
 * A sheet rather than a settings screen, because the question "why is that not on the map" is
 * asked while looking at the map, and is answered by switches in front of you. It also says what
 * it does not do: the drawing screen still draws what is being drawn, and an asset's own page
 * still draws that asset, whatever is switched off here - a switch that left one of those blank
 * would have gone too far.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(
    hidden: Set<AssetLayer>,
    onHide: (layer: AssetLayer, hide: Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    // Opened at its full height rather than half of it: this is four switches and a way to undo
    // them, and half a sheet left the last of them - and the reset - behind a scroll that nothing
    // on the screen says is there.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("What the map draws", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Switch off what you do not need to see. The map remembers, and it is " +
                    "only this map: a spot being drawn, or an asset's own page, still draws it.",
                style = MaterialTheme.typography.bodySmall
            )
            AssetLayer.ALL.forEach { layer ->
                val shown = layer !in hidden
                ListItem(
                    // The whole row switches, rather than only the switch: this is a screen used
                    // with gloves on, and a switch is a small target. Tapping a layer that is on
                    // turns it off, which is what "hide" means here.
                    modifier = Modifier.clickable { onHide(layer, shown) },
                    headlineContent = { Text(layer.displayName) },
                    supportingContent = { Text(layer.summary) },
                    trailingContent = {
                        Switch(checked = shown, onCheckedChange = { checked -> onHide(layer, !checked) })
                    }
                )
            }
            if (hidden.isNotEmpty()) {
                TextButton(onClick = onShowAll) { Text("Show everything") }
            }
        }
    }
}

@Composable
private fun DueLegend(tracks: List<AssetWithDue>, modifier: Modifier = Modifier) {
    if (tracks.isEmpty()) return

    val counts = tracks.groupingBy { it.due.status }.eachCount()
    val overdue = (counts[DueStatus.OVERDUE] ?: 0) + (counts[DueStatus.NEVER_SPRAYED] ?: 0)
    val dueSoon = counts[DueStatus.DUE_SOON] ?: 0
    val notDue = counts[DueStatus.NOT_DUE] ?: 0

    Card(modifier = modifier, shape = RoundedCornerShape(10.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${tracks.size} asset${if (tracks.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall
            )
            LegendRow(AssetColors.RED, "Overdue", overdue)
            LegendRow(AssetColors.YELLOW, "Due soon", dueSoon)
            LegendRow(AssetColors.GREEN, "Not due", notDue)
        }
    }
}

@Composable
private fun LegendRow(colorHex: String, label: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(parseHexColor(colorHex), CircleShape)
        )
        Text(text = "$label  $count", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * What to say when the operator has asked where they are and the app cannot answer.
 *
 * A card rather than a toast, because the reason is worth reading and acting on - location is
 * switched off for the app, or off altogether - and it stays until it has been read.
 */
@Composable
private fun LocationNoticeCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MissingKeyCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.linz_key_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.linz_key_missing),
                style = MaterialTheme.typography.bodySmall
            )
            // The one place where the missing key is the operator's problem, so the fix
            // is a tap away rather than buried in another screen.
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

/**
 * The credit the basemap's licence requires, visible on the map rather than behind a tap.
 *
 * Both licences say the same thing in different words: LINZ requires attribution to be shown,
 * and OpenStreetMap's guidelines add that it must not be hidden "behind toggles, or off-screen".
 * So this strip is drawn by every screen that draws a map, names the basemap actually in use, and
 * links to that basemap's own credit page - which is what makes it satisfy the licence rather than
 * merely mention it.
 */
@Composable
internal fun AttributionStrip(basemap: Basemap, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = basemap.attribution,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .clickable { uriHandler.openUri(basemap.attributionLink) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** "#RRGGBB" to a Compose colour, used to keep map and UI colours identical. */
internal fun parseHexColor(hex: String): Color = runCatching {
    Color(hex.removePrefix("#").toLong(16) or 0xFF000000L)
}.getOrDefault(Color.Gray)
