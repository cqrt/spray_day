package nz.mckenzie.sprayday.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.MethodPhrase
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.due.DuePhrase
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.map.LinzMapView
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.ui.AssetEditFields
import nz.mckenzie.sprayday.ui.AssetEditResult
import nz.mckenzie.sprayday.ui.AssetEdits
import nz.mckenzie.sprayday.ui.formatArea
import nz.mckenzie.sprayday.ui.formatDate
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.ui.formatIntervalDays
import nz.mckenzie.sprayday.ui.formatPlainNumber
import nz.mckenzie.sprayday.ui.formatQuantityWithUnit
import nz.mckenzie.sprayday.viewmodel.AssetDetailViewModel
import nz.mckenzie.sprayday.viewmodel.AssetEditDraft

/**
 * One track: where it is, when it is next due, and what it has been given.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssetDetailScreen(
    viewModel: AssetDetailViewModel,
    onBack: () -> Unit,
    onRecordSpray: () -> Unit,
    onOpenRecording: (Long) -> Unit = {}
) {
    val track by viewModel.track.collectAsStateWithLifecycle()
    val editDraft by viewModel.editDraft.collectAsStateWithLifecycle()
    val geometry by viewModel.geometry.collectAsStateWithLifecycle()
    val bounds by viewModel.bounds.collectAsStateWithLifecycle()
    val due by viewModel.due.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val geoJson = remember(geometry, due, track) {
        AssetGeoJson.build(
            listOf(
                AssetLine(
                    assetId = track?.id ?: 0L,
                    name = track?.name.orEmpty(),
                    colorHex = AssetColors.forStatus(due?.status ?: DueStatus.NEVER_SPRAYED),
                    points = geometry
                )
            )
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let(viewModel::exportGpx) }

    var confirmingDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track?.name ?: "Track") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    LinzMapView(
                        apiKey = apiKey,
                        assetGeoJson = geoJson,
                        fitBounds = bounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    AttributionStrip(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 4.dp)
                    )
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(dueText(due), style = MaterialTheme.typography.titleMedium)
                    editDraft?.groupName?.takeIf { it.isNotBlank() }?.let { group ->
                        Text(text = group, style = MaterialTheme.typography.bodyMedium)
                    }
                    // What it is, and how it is done. The kind is always known, so this
                    // line always says something; the method is left out when nobody has
                    // said, because an empty claim is worse than a gap.
                    track?.let { asset ->
                        val kind = AssetKind.fromStorage(asset.kind)
                        val method = MethodPhrase.of(SprayMethod.fromStorage(asset.method))
                        Text(
                            text = listOf(AssetPhrase.kind(kind), method)
                                .filter { it.isNotBlank() }
                                .joinToString(" \u00b7 "),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "Length ${formatDistance(track?.lengthM ?: 0.0)}" +
                            (viewModel.areaSqm?.let { " \u00b7 about ${formatArea(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${history.size} spray${if (history.size == 1) "" else "s"} recorded" +
                            (track?.intervalDays?.let { " \u00b7 ${formatIntervalDays(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    track?.notes?.takeIf { it.isNotBlank() }?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Wrapping rather than a fixed row: "Record spray" and "Export GPX" are
            // long labels, and on a narrow screen a single row squeezed one of them
            // to nothing.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onRecordSpray) { Text("Record spray") }
                OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                OutlinedButton(
                    onClick = { exportLauncher.launch("${track?.name ?: "track"}.gpx") }
                ) {
                    Text("Export GPX")
                }
            }

            TextButton(onClick = { confirmingDelete = true }) { Text("Delete this track") }

            message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            Text("Spray history", style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) {
                Text(
                    text = "Nothing sprayed yet. Record a spray to start the history.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            history.forEach { entry ->
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formatDate(entry.event.sprayedAtEpochMs),
                            style = MaterialTheme.typography.titleSmall
                        )
                        entry.lines.forEach { line ->
                            Text(
                                text = "${line.name} \u2014 ${formatQuantityWithUnit(line.quantityMl)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        if (entry.lines.isEmpty()) {
                            Text("No products recorded", style = MaterialTheme.typography.bodySmall)
                        }
                        entry.event.waterLitres?.let {
                            Text(text = "Water ${it} L", style = MaterialTheme.typography.bodySmall)
                        }
                        entry.event.distanceM?.let {
                            Text(
                                text = "Driven ${formatDistance(it)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        entry.event.notes?.takeIf { it.isNotBlank() }?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Text("Recordings", style = MaterialTheme.typography.titleSmall)
            if (recordings.isEmpty()) {
                Text(
                    text = "No GPS recording has been made for this track. Recording while " +
                        "spraying leaves the evidence of what was actually driven.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            recordings.forEach { session ->
                Card(onClick = { onOpenRecording(session.id) }) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(session.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${formatDate(session.startedAtEpochMs)} \u00b7 " +
                                "${formatDistance(session.distanceM)} \u00b7 " +
                                "${session.pointCount} point${if (session.pointCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this track?") },
            text = { Text("Its spray history will be deleted with it. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete()
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }

    // The asset and its group are re-read rather than copied once, so a rename while
    // the dialog is open cannot be saved back as a stale name - and the dialog does not
    // open until the group name is known, because a blank field would clear the group.
    editDraft?.takeIf { editing }?.let { current ->
        AssetEditDialog(
            draft = current,
            onDismiss = { editing = false },
            onSave = { asset, groupName ->
                viewModel.save(asset, groupName)
                editing = false
            }
        )
    }
}

/**
 * The per-asset settings: what it is called, the group it is worked with, how often it
 * is sprayed, how wide the boom is, and anything worth remembering about it.
 *
 * The interval used to be the same 120 days for everything, because there was nowhere
 * to change it. It is now this field, and the traffic light follows it.
 *
 * The group is edited as a name, because that is what the operator has in their head;
 * naming one that does not exist yet starts it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssetEditDialog(
    draft: AssetEditDraft,
    onDismiss: () -> Unit,
    onSave: (AssetEntity, String?) -> Unit
) {
    val asset = draft.asset
    var name by remember { mutableStateOf(asset.name) }
    var groupName by remember { mutableStateOf(draft.groupName) }
    var kind by remember { mutableStateOf(AssetKind.fromStorage(asset.kind)) }
    var shape by remember { mutableStateOf(AssetShape.fromStorage(asset.shape)) }
    var method by remember { mutableStateOf(SprayMethod.fromStorage(asset.method)) }
    var intervalDays by remember { mutableStateOf(asset.intervalDays.toString()) }
    var swathWidth by remember {
        mutableStateOf(asset.swathWidthM?.let(::formatPlainNumber).orEmpty())
    }
    var notes by remember { mutableStateOf(asset.notes.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit track") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; problem = null },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; problem = null },
                    label = { Text("Block or area") },
                    supportingText = { Text("Assets sharing one are worked together") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ChoiceRow(
                    label = "What it is",
                    choices = AssetPhrase.kinds,
                    selected = kind,
                    onChoose = { choice ->
                        // A single point is only worth offering for infrastructure, so
                        // moving to another kind puts the shape back to a line rather than
                        // leaving a picnic table's shape sitting on a road.
                        kind = choice
                        if (choice != AssetKind.INFRASTRUCTURE) shape = AssetShape.LINE
                        problem = null
                    },
                    text = AssetPhrase::kind
                )
                if (kind == AssetKind.INFRASTRUCTURE) {
                    ChoiceRow(
                        label = "Shape",
                        choices = AssetPhrase.shapes,
                        selected = shape,
                        onChoose = { shape = it; problem = null },
                        text = AssetPhrase::shapeChoice
                    )
                }
                OutlinedTextField(
                    value = intervalDays,
                    onValueChange = { intervalDays = it; problem = null },
                    label = { Text("Days between sprays") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                ChoiceRow(
                    label = "Spray method",
                    choices = MethodPhrase.choices,
                    selected = method,
                    onChoose = { choice ->
                        // The boom is adjustable, so a width the operator typed is kept;
                        // only a blank field, or the previous method's own default, moves.
                        swathWidth = AssetEdits.swathAfterMethodChange(method, choice, swathWidth)
                        method = choice
                        problem = null
                    },
                    text = MethodPhrase::choice
                )
                OutlinedTextField(
                    value = swathWidth,
                    onValueChange = { swathWidth = it; problem = null },
                    label = { Text("Swath width (m)") },
                    supportingText = { Text("Used for the treated-area estimate; leave empty if unknown") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it; problem = null },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                problem?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Refused edits keep the dialog open with the reason showing: closing it
                // would leave the operator believing the change was stored.
                when (
                    val result = AssetEdits.apply(
                        asset,
                        AssetEditFields(
                            name = name,
                            groupName = groupName,
                            kind = kind,
                            shape = shape,
                            method = method,
                            intervalDays = intervalDays,
                            swathWidthM = swathWidth,
                            notes = notes
                        )
                    )
                ) {
                    is AssetEditResult.Ok -> onSave(result.asset, result.groupName)
                    is AssetEditResult.Invalid -> problem = result.message
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Operator wording for the traffic light. */
internal fun dueText(due: DueInfo?): String =
    due?.let { DuePhrase.of(it.status, it.daysUntilDue) } ?: "Checking due date"

/**
 * One labelled row of single-choice chips.
 *
 * The form has three of these - what an asset is, what shape it is, and how it gets
 * sprayed - and they differ only in their words, so they share one layout rather than
 * three copies of the same FlowRow that could drift apart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    onChoose: (T) -> Unit,
    text: (T) -> String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onChoose(choice) },
                    label = { Text(text(choice)) }
                )
            }
        }
    }
}
