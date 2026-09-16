package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
    onOpenTab: (Tab) -> Unit = {},
    onOpenRecording: (Long) -> Unit
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf<RecordingRow?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Recordings") }) },
        bottomBar = { SprayDayNavBar(current = Tab.RECORDINGS, onSelect = onOpenTab) }
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
                EmptyState(
                    glyph = IconGlyph.RECORDINGS,
                    title = "No recordings yet",
                    body = "A recording is the evidence behind a spray: the line that was " +
                        "actually driven. Start one from the Record tab and it appears here.",
                    actionLabel = "Record a line",
                    onAction = { onOpenTab(Tab.RECORD) }
                )
            } else {
                LazyColumn {
                    itemsIndexed(sessions, key = { _, row -> row.id }) { index, row ->
                        if (index > 0) HorizontalDivider()
                        RecordingListRow(
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
                DestructiveTextButton(
                    text = "Delete",
                    onClick = {
                        viewModel.delete(row.id)
                        confirmingDelete = null
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun RecordingListRow(row: RecordingRow, onOpen: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text(row.name, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A recording still running says so, because that is what the operator is
                // looking for when they scroll back to find it.
                recordingStatus(row)?.let { Text(it) }
                Text(recordingDetail(row))
                Text(recordingAsset(row))
            }
        },
        trailingContent = { DestructiveTextButton(text = "Delete", onClick = onDelete) }
    )
}

/** What a recording is doing, or null once there is nothing left to say about it. */
internal fun recordingStatus(row: RecordingRow): String? = if (row.isFinished) {
    null
} else {
    when (row.status) {
        RecordingStatus.RECORDING -> "still recording"
        RecordingStatus.PAUSED -> "paused"
        RecordingStatus.FINISHED -> null
    }
}

/** When it was, how far it went, and how much of it was kept. */
internal fun recordingDetail(row: RecordingRow): String =
    "${formatDate(row.startedAtEpochMs)} \u00b7 ${formatDistance(row.distanceM)} \u00b7 " +
        "${formatDuration(row.durationMs)} \u00b7 ${row.pointCount} points"

/** Which asset a recording was made for, or that the link to it is gone. */
internal fun recordingAsset(row: RecordingRow): String = when {
    row.assetName != null -> "For ${row.assetName}"
    row.assetWasDeleted -> "Its asset has since been deleted"
    else -> "Not linked to an asset"
}
