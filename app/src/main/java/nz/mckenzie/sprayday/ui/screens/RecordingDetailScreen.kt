package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.formatCoveragePercent
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.LinzMapView
import nz.mckenzie.sprayday.ui.formatDate
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.ui.formatDuration
import nz.mckenzie.sprayday.viewmodel.RecordingDetail
import nz.mckenzie.sprayday.viewmodel.RecordingDetailViewModel
import kotlin.math.abs

/**
 * One recording in full: the line that was driven, the line that was planned, and
 * how much of the plan it covered.
 *
 * Coverage is recomputed from the stored geometry rather than remembered, so this
 * stays truthful even if the planned track has been edited since.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingDetailScreen(
    viewModel: RecordingDetailViewModel,
    onBack: () -> Unit,
    onLogSpray: (Long) -> Unit = {}
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val geoJson by viewModel.geoJson.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()

    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) {
        if (deleted) {
            // Consume before navigating, so re-entering this screen does not
            // immediately bounce back out.
            viewModel.consumeDeleted()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.name ?: "Recording") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LinzMapView(
                apiKey = apiKey,
                trackGeoJson = geoJson,
                fitBounds = remember(detail) { boundsOf(detail) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )

            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                detail?.let { recording ->
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = when {
                                    recording.isFinished -> "Recorded ${formatDate(recording.startedAtEpochMs)}"
                                    else -> "Started ${formatDate(recording.startedAtEpochMs)}, never finished"
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Driven ${formatDistance(recording.geometryDistanceM)} \u00b7 " +
                                    "${formatDuration(recording.durationMs)} \u00b7 " +
                                    "${recording.points.size} points",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (differsFromRecorded(recording)) {
                                Text(
                                    text = "Recorded as ${formatDistance(recording.recordedDistanceM)} " +
                                        "at the time; the distance above is re-derived from the stored points.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = when {
                                    recording.trackName != null -> "Recorded for ${recording.trackName}"
                                    recording.trackId != null -> "Its track has since been deleted"
                                    else -> "Not linked to a planned track"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Coverage", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = when {
                                    recording.points.isEmpty() ->
                                        "Nothing was recorded, so there is nothing to compare."

                                    !recording.hasPlan ->
                                        "No planned line to compare against."

                                    else ->
                                        "Covered ${formatCoveragePercent(recording.coverage ?: 0.0)} of " +
                                            "the planned line, within " +
                                            "${Coverage.DEFAULT_TOLERANCE_M.toInt()} m."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (recording.hasPlan) {
                                Text(
                                    text = "The driven line is drawn in red over the planned line in grey.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    detail?.trackId?.let { trackId ->
                        Button(onClick = { onLogSpray(trackId) }) { Text("Log a spray") }
                    }
                    OutlinedButton(onClick = { confirmingDelete = true }) { Text("Delete recording") }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "Its points go with it. Any spray recorded from it stays in the history, " +
                        "but loses its link to the recording. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/** Camera bounds covering both the driven and the planned line. */
private fun boundsOf(detail: RecordingDetail?): LatLngBounds? {
    val points: List<GeoPoint> = detail?.let { it.points + it.plannedGeometry }.orEmpty()
    if (points.size < 2) return null
    return LatLngBounds(
        minLat = points.minOf { it.lat },
        minLng = points.minOf { it.lng },
        maxLat = points.maxOf { it.lat },
        maxLng = points.maxOf { it.lng }
    )
}

/** True when the stored distance disagrees with the geometry by more than a rounding. */
private fun differsFromRecorded(detail: RecordingDetail): Boolean {
    val stored = detail.recordedDistanceM
    if (stored <= 0.0 || detail.geometryDistanceM <= 0.0) return false
    return abs(stored - detail.geometryDistanceM) / detail.geometryDistanceM > 0.01
}
