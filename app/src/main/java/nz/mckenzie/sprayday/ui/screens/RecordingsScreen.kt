package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.ui.formatDate
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.ui.formatDuration
import nz.mckenzie.sprayday.viewmodel.RecordingRow
import nz.mckenzie.sprayday.viewmodel.RecordingsViewModel

/**
 * Every GPS recording on the device, newest first.
 *
 * Recordings are the evidence behind a spray, and they deliberately outlive the
 * tracks they were recorded against - so a recording whose track has been deleted
 * is still listed, and says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    viewModel: RecordingsViewModel,
    onBack: () -> Unit,
    onOpenRecording: (Long) -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf<RecordingRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Tracks") } }
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
            Text(
                text = "What was actually driven, kept as evidence for the spray records " +
                    "made from it.",
                style = MaterialTheme.typography.bodySmall
            )

            message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            if (sessions.isEmpty()) {
                Text(
                    text = "No recordings yet. Record one when you spray a block.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { row ->
                        RecordingCard(
                            row = row,
                            onOpen = { onOpenRecording(row.id) },
                            onDelete = { confirmingDelete = row }
                        )
                    }
                }
            }
        }
    }

    confirmingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "${row.name}: ${row.pointCount} points, " +
                        "${formatDistance(row.distanceM)}. " +
                        "Any spray recorded from it stays in the history, but loses its " +
                        "link to the recording. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(row.id)
                    confirmingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RecordingCard(row: RecordingRow, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onOpen) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (!row.isFinished) {
                    Text(
                        text = when (row.status) {
                            RecordingStatus.RECORDING -> "still recording"
                            RecordingStatus.PAUSED -> "paused"
                            RecordingStatus.FINISHED -> ""
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                text = "${formatDate(row.startedAtEpochMs)} \u00b7 " +
                    "${formatDistance(row.distanceM)} \u00b7 " +
                    "${formatDuration(row.durationMs)} \u00b7 ${row.pointCount} points",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = when {
                    row.trackName != null -> "For ${row.trackName}"
                    row.trackWasDeleted -> "Its track has since been deleted"
                    else -> "Not linked to a track"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) { Text("Open") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
