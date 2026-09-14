package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.R
import nz.mckenzie.sprayday.data.TrackWithDue
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.map.LinzBasemap
import nz.mckenzie.sprayday.map.LinzMapView
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.viewmodel.MapViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenTracks: () -> Unit = {},
    onOpenOffline: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenTrack: (Long) -> Unit = {}
) {
    val apiKey by viewModel.linzApiKey.collectAsStateWithLifecycle()
    val tracks by viewModel.tracksWithDue.collectAsStateWithLifecycle()
    val geoJson by viewModel.trackGeoJson.collectAsStateWithLifecycle()
    val initialFrame by viewModel.initialFrame.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenTracks) { Text("Tracks") }
                    TextButton(onClick = onOpenOffline) { Text("Offline") }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LinzMapView(
                apiKey = apiKey,
                trackGeoJson = geoJson,
                // The operator's own tracks first; failing that, where the device is;
                // failing that, the neutral country-wide default.
                fitBounds = initialFrame,
                // Tapping a track opens it: the map is where they are looking when they
                // wonder about a block. The radius comes from the map's zoom, so the tap
                // works at country scale as well as at spray height.
                onMapClick = { lat, lng, radiusM -> viewModel.trackAt(lat, lng, radiusM)?.let(onOpenTrack) },
                modifier = Modifier.fillMaxSize()
            )

            DueLegend(
                tracks = tracks,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )

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
private fun DueLegend(tracks: List<TrackWithDue>, modifier: Modifier = Modifier) {
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
                text = "${tracks.size} track${if (tracks.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall
            )
            LegendRow(TrackColors.RED, "Overdue", overdue)
            LegendRow(TrackColors.YELLOW, "Due soon", dueSoon)
            LegendRow(TrackColors.GREEN, "Not due", notDue)
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

@Composable
private fun MissingKeyCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
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
