package nz.mckenzie.sprayday.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.data.TrackWithDue
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.viewmodel.TrackListViewModel

/**
 * The track library: what tracks exist, how long they are, and when each is next
 * due. Tracks can be imported from GPX or drawn on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onDrawTrack: () -> Unit,
    onRecordTrack: () -> Unit
) {
    val tracks by viewModel.tracksWithDue.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importGpx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracks") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Map") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { importLauncher.launch(GPX_MIME_TYPES) },
                    enabled = !busy
                ) {
                    Text("Import GPX")
                }
                OutlinedButton(onClick = onDrawTrack) { Text("Draw") }
                OutlinedButton(onClick = onRecordTrack) { Text("Record") }
            }

            message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }

            if (tracks.isEmpty()) {
                Text(
                    text = "No tracks yet. Import a GPX file, or draw one on the map.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { it.track.id }) { item ->
                        TrackRow(item = item, onDelete = { viewModel.delete(item.track.id) })
                    }
                }
            }
        }
    }
}

private val GPX_MIME_TYPES = arrayOf(
    "application/gpx+xml",
    "application/xml",
    "text/xml",
    "text/plain",
    "*/*"
)

@Composable
private fun TrackRow(item: TrackWithDue, onDelete: () -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(parseHexColor(TrackColors.forStatus(item.due.status)), CircleShape)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(item.track.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${formatDistance(item.track.lengthM)} \u00b7 ${dueLabel(item)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** Operator wording for the traffic light, so the colour is never a mystery. */
internal fun dueLabel(item: TrackWithDue): String = when (item.due.status) {
    DueStatus.NEVER_SPRAYED -> "never sprayed"
    else -> {
        val days = item.due.daysUntilDue ?: 0L
        when {
            days < 0L -> "${-days} days overdue"
            days == 0L -> "due today"
            days == 1L -> "due tomorrow"
            else -> "due in $days days"
        }
    }
}
