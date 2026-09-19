package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.map.BasemapView
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.offline.OfflineAreaPlan
import nz.mckenzie.sprayday.ui.formatCoordinates
import nz.mckenzie.sprayday.viewmodel.AreaSource
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel

/**
 * Downloads basemap imagery for the operator's own area so the map still works where
 * there is no reception, and manages what is already stored.
 *
 * The screen says which area it is about to cache, in degrees and drawn on a map,
 * because "Spray area" with no location is not an answer to "what am I downloading?".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    viewModel: OfflineViewModel,
    onOpenTab: (Tab) -> Unit = {},
    onChooseArea: () -> Unit
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val areaSource by viewModel.areaSource.collectAsStateWithLifecycle()
    val previewGeoJson by viewModel.previewGeoJson.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Taken from the list, which is the database, so the bar tracks the download as it
    // writes its progress rather than needing a second source of truth.
    val active: OfflineArea? = activeId?.let { id -> stored.firstOrNull { it.id == id } }
    var confirmingClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Offline areas") }) },
        bottomBar = { SprayDayNavBar(current = Tab.OFFLINE, onSelect = onOpenTab) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Picking by hand, for a block that is not where the phone happens to be.
            OutlinedButton(
                onClick = onChooseArea,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose an area on the map")
            }

            plan?.let { area ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(area.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Centre ${centreOf(area)} \u2014 ${sourceLabel(areaSource)}",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // The outline is drawn as a closed line, so what is about to be
                        // cached is visible rather than described.
                        //
                        // Aerial imagery whatever the operator has chosen for their own maps:
                        // this thumbnail is of the thing being downloaded, and OpenStreetMap
                        // can never be downloaded.
                        Box {
                            BasemapView(
                                basemap = Basemap.LINZ_AERIAL,
                                apiKey = apiKey,
                                assetGeoJson = previewGeoJson,
                                fitBounds = area.bounds,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            )
                            // The licence wants the credit where the imagery is, and this
                            // thumbnail is a place imagery is shown: the same strip the map
                            // and the asset screens carry, for the same reason.
                            AttributionStrip(
                                basemap = Basemap.LINZ_AERIAL,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(bottom = 4.dp)
                            )
                        }

                        Text(
                            text = "About %.1f km across, zoom %d to %d".format(
                                area.approxWidthKm, area.minZoom, area.maxZoom
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${area.tileCount} aerial tiles, roughly ${area.estimatedSizeLabel}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Exactly those tiles are fetched and stored on the device, so " +
                                "the map keeps working with no reception.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        // Said here, where the operator is looking at aerial imagery under a
                        // basemap of their own choosing, because "why is this not the map I
                        // picked?" is the question this screen otherwise leaves them with.
                        Text(
                            text = "Offline areas are always aerial imagery: it is the only " +
                                "basemap whose licence allows downloading ahead of time.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Button(
                onClick = { viewModel.download() },
                enabled = apiKey.isNotBlank() && !working && plan != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        working -> "Downloading…"
                        active?.isComplete == true -> "Download again"
                        else -> "Download area"
                    }
                )
            }

            if (apiKey.isBlank()) {
                Text(
                    text = "Add a LINZ Basemaps key in Settings before downloading.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            active?.let { area ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (area.isComplete) "Downloaded" else "Downloading ${area.percent}%",
                            style = MaterialTheme.typography.titleSmall
                        )
                        LinearProgressIndicator(
                            progress = { area.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${area.sizeLabel} on disk, " +
                                "${area.storedTiles} of ${area.plannedTiles} tiles",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (area.isShortButComplete) {
                            Text(
                                text = "${area.missingTiles} tiles in this area have no " +
                                    "imagery available from LINZ, so they will never arrive.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        area.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Stored areas (${stored.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    // What is really on the device, including tiles kept from
                    // ordinary map browsing - not just what these areas asked for.
                    text = "${summary.tiles} tiles · ${summary.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (stored.isEmpty()) {
                EmptyState(
                    glyph = IconGlyph.OFFLINE,
                    title = "Nothing downloaded yet",
                    body = "Choose an area and it is stored on the phone, so the map keeps " +
                        "working where there is no reception.",
                    actionLabel = "Choose an area",
                    onAction = onChooseArea
                )
            }

            stored.forEach { area ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(area.name, style = MaterialTheme.typography.bodyMedium)
                        Text(areaStatus(area), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!area.isComplete) {
                                TextButton(
                                    onClick = { viewModel.resume(area.id) },
                                    enabled = apiKey.isNotBlank() && !working
                                ) { Text("Resume") }
                            }
                            DestructiveTextButton(text = "Delete", onClick = { viewModel.delete(area.id) })
                        }
                    }
                }
            }

            if (summary.tiles > 0L) {
                DestructiveTextButton(
                    text = "Clear downloaded imagery",
                    onClick = { confirmingClear = true }
                )
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Delete downloaded imagery?") },
            text = {
                Text(
                    "This removes all ${summary.tiles} tiles (${summary.sizeLabel}) and the " +
                        "list of areas. The map will need reception until areas are " +
                        "downloaded again."
                )
            },
            confirmButton = {
                DestructiveTextButton(
                    text = "Delete",
                    onClick = {
                        confirmingClear = false
                        viewModel.clearTiles()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
            }
        )
    }
}

/** The middle of the area, in degrees - the same numbers a GPS would show. */
private fun centreOf(area: OfflineAreaPlan): String = formatCoordinates(
    lat = (area.bounds.minLat + area.bounds.maxLat) / 2.0,
    lng = (area.bounds.minLng + area.bounds.maxLng) / 2.0
)

private fun sourceLabel(source: AreaSource): String = when (source) {
    AreaSource.MY_LOCATION -> "your current location"
    AreaSource.MY_TRACKS -> "the middle of your assets"
    AreaSource.UNKNOWN -> "an unknown area"
}

/** One line describing where an area got to, honest about anything missing. */
private fun areaStatus(area: OfflineArea): String = when {
    area.isComplete && area.missingTiles == 0 ->
        "${area.plannedTiles} tiles · ${area.sizeLabel}"

    area.isComplete ->
        "${area.storedTiles} of ${area.plannedTiles} tiles · ${area.sizeLabel} " +
            "(${area.missingTiles} have no imagery at LINZ)"

    else ->
        "${area.storedTiles} of ${area.plannedTiles} tiles · ${area.percent}%"
}
