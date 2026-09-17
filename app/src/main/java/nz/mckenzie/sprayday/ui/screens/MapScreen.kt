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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.map.LinzBasemap
import nz.mckenzie.sprayday.map.LinzMapView
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
    val tracks by viewModel.assetsWithDue.collectAsStateWithLifecycle()
    val geoJson by viewModel.assetGeoJson.collectAsStateWithLifecycle()
    val initialFrame by viewModel.initialFrame.collectAsStateWithLifecycle()
    val positionGeoJson by viewModel.positionGeoJson.collectAsStateWithLifecycle()
    val recentre by viewModel.recentre.collectAsStateWithLifecycle()
    val locationNotice by viewModel.locationNotice.collectAsStateWithLifecycle()

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
                // Settings is all the map's own bar still holds. What used to be words up
                // here - Assets, Offline - are tabs in the bar below now, which is where a
                // place belongs; and the key is set once, so the icon is enough.
                actions = {
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
            LinzMapView(
                apiKey = apiKey,
                assetGeoJson = geoJson,
                // Where the phone is: a dot, ringed by the accuracy it was fixed to. An empty
                // collection - nothing drawn - when the app is not allowed to know.
                positionGeoJson = positionGeoJson,
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

            if (apiKey.isBlank()) {
                MissingKeyCard(
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }

            AttributionStrip(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 6.dp)
            )
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

/** The LINZ licence requires attribution to be visible, not hidden behind a tap. */
@Composable
internal fun AttributionStrip(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = LinzBasemap.ATTRIBUTION,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .clickable { uriHandler.openUri(LinzBasemap.ATTRIBUTION_LINK) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/** "#RRGGBB" to a Compose colour, used to keep map and UI colours identical. */
internal fun parseHexColor(hex: String): Color = runCatching {
    Color(hex.removePrefix("#").toLong(16) or 0xFF000000L)
}.getOrDefault(Color.Gray)
