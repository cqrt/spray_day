package nz.mckenzie.sprayday.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.domain.geo.formatCoveragePercent
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.map.LinzMapView
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.ui.formatDuration
import nz.mckenzie.sprayday.viewmodel.RecordingViewModel

/**
 * Records a GPS track, and records the spray while doing it.
 *
 * Location is requested here (rather than at app launch) so the permission prompt
 * arrives with an obvious reason. Choosing the track being sprayed turns this into
 * the whole job in one screen: the planned line is drawn behind the one being
 * driven, the products are pre-filled from last time, the coverage tells you
 * whether the whole line is done, and finishing saves the recording and the spray
 * together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(viewModel: RecordingViewModel, onOpenTab: (Tab) -> Unit = {}) {
    val state by viewModel.tracking.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val geoJson by viewModel.recordedGeoJson.collectAsStateWithLifecycle()
    val initialFrame by viewModel.initialFrame.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val coverage by viewModel.coverage.collectAsStateWithLifecycle()
    val trackLengthM by viewModel.trackLengthM.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val assetName by viewModel.selectedTrackName.collectAsStateWithLifecycle()
    val tracks by viewModel.assetsToSpray.collectAsStateWithLifecycle()
    val remember by viewModel.rememberDefaults.collectAsStateWithLifecycle()
    val pendingTrackName by viewModel.pendingTrackName.collectAsStateWithLifecycle()

    var permissionGranted by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var pickingTrack by remember { mutableStateOf(false) }
    var assetNameDraft by remember { mutableStateOf("") }

    // The picker opens fully rather than at half height. Half a sheet is a third of the
    // blocks hidden below the fold, and the row that matters - the one just recorded, or
    // "no asset" - is as likely as any other to be the hidden one.
    val pickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Finish asks what to call the line; the suggested name arrives with the prompt.
    LaunchedEffect(pendingTrackName) {
        pendingTrackName?.let { assetNameDraft = it }
    }

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
        topBar = { TopAppBar(title = { Text("Record") }) },
        // No back arrow: recording is a tab, and the bar below is how you leave it. The
        // arrow that used to be here sent the operator to the asset list, which is not
        // where they came from.
        bottomBar = { SprayDayNavBar(current = Tab.RECORD, onSelect = onOpenTab) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LinzMapView(
                apiKey = apiKey,
                assetGeoJson = geoJson,
                // Recording starts where the operator is, not on a view of the country.
                fitBounds = initialFrame,
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
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
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
                            "\u00b7 ${state.pointCount} ${if (state.pointCount == 1) "point" else "points"}",
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

                    coverage?.let { covered ->
                        Text(
                            text = "Covered ${formatCoveragePercent(covered)} of " +
                                (assetName ?: "the line"),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }

                    trackLengthM?.let { length ->
                        Text(
                            text = "Track ${formatDistance(length)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

                    TextButton(onClick = { pickingTrack = true }) {
                        Text(assetName?.let { "Spraying: $it" } ?: "Choose the asset being sprayed")
                    }

                    if (assetName != null && rows.isNotEmpty()) {
                        Text("Spray used", style = MaterialTheme.typography.titleSmall)
                        rows.forEach { row ->
                            QuantityRow(
                                name = row.name,
                                value = row.quantityText,
                                onChange = { text -> viewModel.updateQuantity(row.productId, text) }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = remember, onCheckedChange = viewModel::setRememberDefaults)
                            Text(
                                text = "Remember these amounts for this asset",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (!permissionGranted) {
                        Button(
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (state.status) {
                                RecordingStatus.RECORDING -> {
                                    OutlinedButton(onClick = viewModel::pause) { Text("Pause") }
                                    Button(onClick = viewModel::finish) { Text("Finish") }
                                }

                                RecordingStatus.PAUSED -> {
                                    Button(onClick = viewModel::resume) { Text("Resume") }
                                    OutlinedButton(onClick = viewModel::finish) { Text("Finish") }
                                }

                                else -> {
                                    Button(onClick = viewModel::start) { Text("Start recording") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingTrack) {
        ModalBottomSheet(
            onDismissRequest = { pickingTrack = false },
            sheetState = pickerSheetState
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = "Which asset are you spraying?",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                )
                if (tracks.isEmpty()) {
                    Text(
                        text = "Nothing planned yet. You can still record a line.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
                // The rows scroll inside a window of their own rather than running off the
                // bottom of the sheet: a season is dozens of blocks, and the sheet's content
                // does not scroll by itself, so everything past the screen edge - a track
                // recorded this morning included - could not be reached at all.
                LazyColumn(modifier = Modifier.heightIn(max = PICKER_LIST_MAX_HEIGHT)) {
                    itemsIndexed(tracks, key = { _, item -> item.asset.id }) { index, item ->
                        if (index > 0) HorizontalDivider()
                        ListItem(
                            modifier = Modifier.clickable {
                                viewModel.selectTrack(item.asset.id)
                                pickingTrack = false
                            },
                            headlineContent = { Text(item.asset.name) },
                            // The due colour travels with the name, because which block to
                            // spray next is the question this sheet is answering.
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            parseHexColor(AssetColors.forStatus(item.due.status)),
                                            CircleShape
                                        )
                                )
                            }
                        )
                    }
                }
                if (tracks.isNotEmpty()) HorizontalDivider()
                // Recording without choosing an asset stays possible: the line that was
                // driven is the evidence, and which asset it belongs to can be settled later.
                // It sits outside the scrolling rows, so it stays one tap away however long
                // the list gets.
                ListItem(
                    modifier = Modifier.clickable {
                        viewModel.selectTrack(null)
                        pickingTrack = false
                    },
                    headlineContent = { Text("Just record, no asset") },
                    leadingContent = { AppIcon(IconGlyph.RECORD) }
                )
            }
        }
    }

    // Finishing a line that is not already a track makes one, so it needs a name.
    if (pendingTrackName != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelFinish() },
            title = { Text("Name this line") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "The line you recorded is saved as an asset, so it appears on " +
                            "the Assets page and can be sprayed again.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = assetNameDraft,
                        onValueChange = { assetNameDraft = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmFinish(assetNameDraft) }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelFinish() }) { Text("Keep recording") }
            }
        )
    }
}

/**
 * How tall the picker's rows may get before they scroll.
 *
 * About seven rows - the height the sheet happened to come to before - so the list looks
 * the same as it always did, and the blocks below it are reached by scrolling the rows
 * rather than lost off the bottom of the screen. The title and "Just record, no asset"
 * stay outside it, because those are the two things that must not need finding.
 */
private val PICKER_LIST_MAX_HEIGHT = 400.dp

/** One product and the amount that went in, typed where it is being sprayed. */
@Composable
private fun QuantityRow(name: String, value: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("mL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(120.dp)
        )
    }
}
