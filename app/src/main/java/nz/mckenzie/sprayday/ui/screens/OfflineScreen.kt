package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel

/**
 * Downloads basemap imagery for the spray area so the map still works where
 * there is no reception, and lists what is already stored on the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineScreen(viewModel: OfflineViewModel, onBack: () -> Unit) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val stored by viewModel.stored.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val warning by viewModel.warning.collectAsStateWithLifecycle()

    val plan = viewModel.plan
    val downloading = active?.isComplete == false

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
                        text = "The real download is larger: LINZ's hosted style also declares " +
                            "terrain sources that MapLibre caches alongside the imagery.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Imagery is stored on the device, so the map keeps working " +
                            "with no reception.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = { viewModel.download() },
                enabled = apiKey.isNotBlank() && !downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (active?.isComplete == true) "Download again" else "Download area")
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
                            text = if (area.isComplete || area.stalled) {
                                "Downloaded"
                            } else {
                                "Downloading ${area.percent}%"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { area.percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${area.sizeLabel} on disk, " +
                                "${area.completedResources} of ${area.requiredResources} files",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            warning?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Text("Stored areas (${stored.size})", style = MaterialTheme.typography.titleSmall)
            if (stored.isEmpty()) {
                Text("Nothing stored yet.", style = MaterialTheme.typography.bodySmall)
            }
            stored.forEach { area ->
                Card {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            text = area.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { viewModel.delete(area.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
