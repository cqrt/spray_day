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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.map.BasemapView
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel

/**
 * What is downloaded for offline use, in two tabs: basemap imagery, and DOC's tracks.
 *
 * The two are **independent caches** - imagery makes the map work with no reception, DOC's tracks
 * make the DOC tracks browser work with no reception - so they are chosen, counted and cleared
 * separately, and neither can fill the other's tab. Choosing an area is the map picker either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(
    viewModel: OfflineViewModel,
    onOpenTab: (Tab) -> Unit = {},
    onChooseArea: () -> Unit,
    onChooseDocArea: () -> Unit
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val docTrackCount by viewModel.docTrackCount.collectAsStateWithLifecycle()
    val docBounds by viewModel.docBounds.collectAsStateWithLifecycle()

    var chosenTab by rememberSaveable { mutableStateOf(0) }
    var confirmingClearImagery by rememberSaveable { mutableStateOf(false) }
    var confirmingClearDoc by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Offline areas") }) },
        bottomBar = { SprayDayNavBar(current = Tab.OFFLINE, onSelect = onOpenTab) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = chosenTab) {
                Tab(
                    selected = chosenTab == 0,
                    onClick = { chosenTab = 0 },
                    text = { Text("Imagery") }
                )
                Tab(
                    selected = chosenTab == 1,
                    onClick = { chosenTab = 1 },
                    text = { Text("DOC tracks") }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (chosenTab == 0) {
                    ImageryTab(
                        apiKey = apiKey,
                        stored = stored,
                        working = working,
                        summaryLabel = "${summary.tiles} tiles · ${summary.sizeLabel}",
                        showClear = summary.tiles > 0L,
                        error = error,
                        onChooseArea = onChooseArea,
                        onResume = viewModel::resume,
                        onDelete = viewModel::delete,
                        onClear = { confirmingClearImagery = true }
                    )
                } else {
                    DocTracksTab(
                        apiKey = apiKey,
                        count = docTrackCount,
                        bounds = docBounds,
                        onChooseArea = onChooseDocArea,
                        onClear = { confirmingClearDoc = true }
                    )
                }
            }
        }
    }

    if (confirmingClearImagery) {
        AlertDialog(
            onDismissRequest = { confirmingClearImagery = false },
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
                        confirmingClearImagery = false
                        viewModel.clearTiles()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearImagery = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmingClearDoc) {
        AlertDialog(
            onDismissRequest = { confirmingClearDoc = false },
            title = { Text("Delete downloaded DOC tracks?") },
            text = {
                Text(
                    "This removes the $docTrackCount DOC ${if (docTrackCount == 1) "track" else "tracks"} " +
                        "kept for offline use. The DOC tracks browser will need reception until an " +
                        "area is downloaded again. Nothing already imported is touched."
                )
            },
            confirmButton = {
                DestructiveTextButton(
                    text = "Delete",
                    onClick = {
                        confirmingClearDoc = false
                        viewModel.clearDocTracks()
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearDoc = false }) { Text("Cancel") }
            }
        )
    }
}

/** Imagery for the map: the areas chosen on it, and the tiles they hold. */
@Composable
private fun ImageryTab(
    apiKey: String,
    stored: List<OfflineArea>,
    working: Boolean,
    summaryLabel: String,
    showClear: Boolean,
    error: String?,
    onChooseArea: () -> Unit,
    onResume: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit
) {
    Text(
        text = "Aerial imagery for the map where there is no reception. Choose an area on the map " +
            "and the tiles inside it are stored on the phone.",
        style = MaterialTheme.typography.bodySmall
    )

    OutlinedButton(onClick = onChooseArea, modifier = Modifier.fillMaxWidth()) {
        Text("Choose an area on the map")
    }

    if (apiKey.isBlank()) {
        Text(
            text = "Add a LINZ Basemaps key in Settings before downloading imagery.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    error?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Stored areas (${stored.size})",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall
        )
        Text(text = summaryLabel, style = MaterialTheme.typography.bodySmall)
    }

    if (stored.isEmpty()) {
        EmptyState(
            glyph = IconGlyph.OFFLINE,
            title = "No imagery downloaded yet",
            body = "Choose an area and its tiles are stored on the phone, so the map keeps " +
                "working where there is no reception.",
            actionLabel = "Choose an area",
            onAction = onChooseArea
        )
    } else {
        stored.forEach { area ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // Where the area is, over the imagery it cached: a listing says where as well as
                    // what, and the white box is the ground the tiles cover.
                    CachedAreaMap(bounds = area.bounds, apiKey = apiKey)
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
                                TextButton(onClick = { onResume(area.id) }, enabled = !working) {
                                    Text("Resume")
                                }
                            }
                            DestructiveTextButton(text = "Delete", onClick = { onDelete(area.id) })
                        }
                    }
                }
            }
        }
    }

    if (showClear) {
        DestructiveTextButton(text = "Clear downloaded imagery", onClick = onClear)
    }
}

/** DOC's tracks, for the DOC tracks browser to import with no reception. */
@Composable
private fun DocTracksTab(
    apiKey: String,
    count: Int,
    bounds: LatLngBounds?,
    onChooseArea: () -> Unit,
    onClear: () -> Unit
) {
    Text(
        text = "The Department of Conservation's tracks, for the DOC tracks browser to import " +
            "with no reception. It is separate from the imagery: no LINZ key is needed, and " +
            "nothing here appears on the map by itself.",
        style = MaterialTheme.typography.bodySmall
    )

    OutlinedButton(onClick = onChooseArea, modifier = Modifier.fillMaxWidth()) {
        Text("Choose an area on the map")
    }

    if (count == 0) {
        EmptyState(
            glyph = IconGlyph.MAP,
            title = "No DOC tracks downloaded",
            body = "Choose an area and every DOC track inside it is downloaded, so the DOC tracks " +
                "browser can import them with no reception.",
            actionLabel = "Choose an area",
            onAction = onChooseArea
        )
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                // The ground the cached tracks cover: a cache of tracks has no box of its own, so
                // this is the extent of the tracks themselves.
                bounds?.let { CachedAreaMap(bounds = it, apiKey = apiKey) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$count DOC ${if (count == 1) "track" else "tracks"} downloaded",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Available to the DOC tracks browser with no reception.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        DestructiveTextButton(text = "Clear downloaded DOC tracks", onClick = onClear)
    }
}

/**
 * Where a cache is: the area drawn over the imagery, fitted so the whole box is in the frame.
 *
 * Aerial imagery whatever basemap the operator reads their own maps on, for the same reason the
 * picker uses it: this is a picture of what was downloaded, and imagery is the only basemap whose
 * licence allows downloading ahead of time.
 */
@Composable
private fun CachedAreaMap(bounds: LatLngBounds, apiKey: String) {
    Box {
        BasemapView(
            basemap = Basemap.LINZ_AERIAL,
            apiKey = apiKey,
            assetGeoJson = boundsOutline(bounds),
            fitBounds = bounds,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )
        AttributionStrip(
            basemap = Basemap.LINZ_AERIAL,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 4.dp, bottom = 4.dp)
        )
    }
}

private const val CACHED_AREA_OUTLINE_ID = -1L

/** White, because it is the one colour the app uses for nothing else - see the picker's own outline. */
private const val CACHED_AREA_OUTLINE_COLOUR = "#FFFFFF"

/** A box as a closed line, which is all the map's line layer needs to draw it. */
private fun boundsOutline(bounds: LatLngBounds): String = AssetGeoJson.build(
    listOf(
        AssetLine(
            assetId = CACHED_AREA_OUTLINE_ID,
            name = "Cached area",
            colorHex = CACHED_AREA_OUTLINE_COLOUR,
            points = listOf(
                GeoPoint(bounds.minLat, bounds.minLng),
                GeoPoint(bounds.maxLat, bounds.minLng),
                GeoPoint(bounds.maxLat, bounds.maxLng),
                GeoPoint(bounds.minLat, bounds.maxLng),
                GeoPoint(bounds.minLat, bounds.minLng)
            )
        )
    )
)

/** One line describing where an imagery area got to, honest about anything missing. */
private fun areaStatus(area: OfflineArea): String = when {
    area.isComplete && area.missingTiles == 0 ->
        "${area.plannedTiles} tiles · ${area.sizeLabel}"

    area.isComplete ->
        "${area.storedTiles} of ${area.plannedTiles} tiles · ${area.sizeLabel} " +
            "(${area.missingTiles} have no imagery at LINZ)"

    else ->
        "${area.storedTiles} of ${area.plannedTiles} tiles · ${area.percent}%"
}
