package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel

/**
 * Downloads basemap imagery for the spray area so the map still works where
 * there is no reception, and manages what is stored on the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(viewModel: OfflineViewModel, onBack: () -> Unit) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val plan = viewModel.plan
    // Taken from the list, which is the database, so the bar tracks the download
    // as it writes its progress rather than needing a second source of truth.
    val active: OfflineArea? = activeId?.let { id -> stored.firstOrNull { it.id == id } }
    var confirmingClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Offline areas") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Map") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(plan.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "About %.1f km across, zoom %d to %d".format(
                            plan.approxWidthKm, plan.minZoom, plan.maxZoom
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${plan.tileCount} aerial tiles, roughly ${plan.estimatedSizeLabel}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Exactly those tiles are fetched and stored on the device, so " +
                            "the map keeps working with no reception.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = { viewModel.download() },
                enabled = apiKey.isNotBlank() && !working,
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
                    text = "Add a LINZ Basemaps key in local.properties before downloading.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            active?.let { area ->
                Card {
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
                Text(
                    text = "Nothing stored yet. The map also keeps the imagery you browse.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            stored.forEach { area ->
                Card {
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
                            TextButton(onClick = { viewModel.delete(area.id) }) { Text("Delete") }
                        }
                    }
                }
            }

            if (summary.tiles > 0L) {
                TextButton(onClick = { confirmingClear = true }) {
                    Text("Clear downloaded imagery")
                }
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
                TextButton(onClick = {
                    confirmingClear = false
                    viewModel.clearTiles()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
            }
        )
    }
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
