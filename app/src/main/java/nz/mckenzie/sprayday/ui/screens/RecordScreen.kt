package nz.mckenzie.sprayday.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.map.LinzMapView
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.ui.formatDuration
import nz.mckenzie.sprayday.viewmodel.RecordingViewModel

/**
 * Records a GPS track. Location is requested here (rather than at app launch) so
 * the permission prompt arrives with an obvious reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(viewModel: RecordingViewModel, onBack: () -> Unit) {
    val state by viewModel.tracking.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val geoJson by viewModel.recordedGeoJson.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var permissionGranted by remember { mutableStateOf(viewModel.hasLocationPermission()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!permissionGranted) viewModel.onPermissionDenied()
    }

    // Tick once a second so the elapsed time moves while recording.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.status) {
        while (state.status == RecordingStatus.RECORDING) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedMs = state.startedAtEpochMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record track") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
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
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when (state.status) {
                            RecordingStatus.RECORDING -> "Recording"
                            RecordingStatus.PAUSED -> "Paused"
                            RecordingStatus.FINISHED -> "Finished"
                            null -> "Ready to record"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${formatDistance(state.distanceM)} \u00b7 ${formatDuration(elapsedMs)} " +
                            "\u00b7 ${state.pointCount} points",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    state.lastAccuracyM?.let { accuracy ->
                        Text(
                            text = "Accuracy \u00b1${accuracy.toInt()} m" +
                                if (state.rejectedFixes > 0) {
                                    " \u00b7 ${state.rejectedFixes} fixes rejected"
                                } else {
                                    ""
                                },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

                    if (!permissionGranted) {
                        androidx.compose.material3.Button(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Allow location to record")
                        }
                    } else {
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (state.status) {
                                RecordingStatus.RECORDING -> {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = viewModel::pause
                                    ) { Text("Pause") }
                                    androidx.compose.material3.Button(
                                        onClick = viewModel::finish
                                    ) { Text("Finish") }
                                }

                                RecordingStatus.PAUSED -> {
                                    androidx.compose.material3.Button(
                                        onClick = viewModel::resume
                                    ) { Text("Resume") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = viewModel::finish
                                    ) { Text("Finish") }
                                }

                                else -> {
                                    androidx.compose.material3.Button(
                                        onClick = viewModel::start
                                    ) { Text("Start recording") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
