package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.map.LinzMapView
import nz.mckenzie.sprayday.offline.OfflineAreaDraft
import nz.mckenzie.sprayday.viewmodel.OfflineAreaPickerViewModel
import kotlin.math.roundToInt

/**
 * Picks an offline area on the map: two taps for the corners, the zoom levels to
 * cache, and a name to find it by.
 *
 * Every choice shows its cost immediately. That matters more than it sounds: the
 * difference between caching to zoom 16 and to zoom 17 is the difference between a
 * minute and a quarter of an hour on a farm connection, and the operator is the only
 * one who knows whether they need to see individual rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineAreaPickerScreen(
    viewModel: OfflineAreaPickerViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val previewGeoJson by viewModel.previewGeoJson.collectAsStateWithLifecycle()
    val startBounds by viewModel.startBounds.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    LaunchedEffect(saved) {
        if (saved) {
            // Consume before leaving, so a reused view model does not bounce the
            // operator straight back out next time.
            viewModel.consumeSaved()
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose an area") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Offline areas") } }
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
                assetGeoJson = previewGeoJson,
                fitBounds = startBounds,
                onMapClick = { latitude, longitude, _ -> viewModel.tapAt(latitude, longitude) },
                modifier = Modifier.fillMaxSize()
            )

            AttributionStrip(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 6.dp)
            )

            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 40.dp)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when (draft.corners.size) {
                            0 -> "Tap one corner of the block you want to cache"
                            1 -> "Now tap the opposite corner"
                            else -> "Tap again to start a new box, or drag the map to check it"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )

                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = viewModel::setName,
                        label = { Text("Area name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ZoomSlider(
                        label = "Shallowest level",
                        value = draft.minZoom,
                        onChange = { viewModel.setZoomRange(it, draft.maxZoom) }
                    )
                    ZoomSlider(
                        label = "Deepest level",
                        value = draft.maxZoom,
                        onChange = { viewModel.setZoomRange(draft.minZoom, it) }
                    )

                    Text(
                        text = if (draft.isComplete) {
                            "${draft.tileCount} aerial tiles, roughly ${draft.estimatedSizeLabel} " +
                                "· zoom ${draft.minZoom} to ${draft.maxZoom}"
                        } else {
                            "Tap two corners to see how many tiles that is."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Each deeper level multiplies the download. The shallow ones are " +
                            "nearly free, and are what let the map open on a zoomed-out view " +
                            "with no reception.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (draft.isTooBig) {
                        Text(
                            text = "That is too much to cache in one go. Narrow the box or the " +
                                "zoom range.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    active?.let { area ->
                        Text(
                            text = if (area.isComplete) "Downloaded ${area.sizeLabel}" else "Downloading ${area.percent}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::clearCorners,
                            enabled = draft.corners.isNotEmpty() && !working
                        ) { Text("Start over") }
                        Button(
                            onClick = viewModel::download,
                            enabled = apiKey.isNotBlank() && draft.isComplete &&
                                !draft.isTooBig && !working
                        ) { Text("Download area") }
                    }
                }
            }
        }
    }
}

/** A zoom level, as a slider with the number spelled out. */
@Composable
private fun ZoomSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text(
            text = "$label: zoom $value",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = OfflineAreaDraft.MIN_ZOOM.toFloat()..OfflineAreaDraft.MAX_ZOOM.toFloat(),
            // Twelve whole levels, so eleven gaps and ten intermediate stops.
            steps = OfflineAreaDraft.MAX_ZOOM - OfflineAreaDraft.MIN_ZOOM - 1
        )
    }
}
