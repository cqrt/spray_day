package nz.mckenzie.sprayday.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import nz.mckenzie.sprayday.ui.formatArea
import nz.mckenzie.sprayday.ui.formatDate
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.ui.formatIntervalDays
import nz.mckenzie.sprayday.ui.formatQuantityWithUnit
import nz.mckenzie.sprayday.viewmodel.AssetDetailViewModel

/**
 * One track: where it is, when it is next due, and what it has been given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    viewModel: AssetDetailViewModel,
    onBack: () -> Unit,
    onRecordSpray: () -> Unit,
    // Editing happens on a screen of its own, so this screen only has to say where to go.
    onEdit: () -> Unit,
    onOpenRecording: (Long) -> Unit = {}
) {
    val track by viewModel.track.collectAsStateWithLifecycle()
    val editDraft by viewModel.editDraft.collectAsStateWithLifecycle()
    val groupName by viewModel.groupName.collectAsStateWithLifecycle()
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
                    points = geometry,
                    kind = AssetKind.fromStorage(track?.kind),
                    shape = AssetShape.fromStorage(track?.shape)
                )
            )
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri -> uri?.let(viewModel::exportGpx) }

    var confirmingDelete by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track?.name ?: "Asset") },
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(IconGlyph.BACK, contentDescription = "Back") } },
                actions = {
                    // Everything that is not "record a spray" lives behind this: editing the
                    // asset, exporting it, deleting it. Material puts those in a sheet rather
                    // than as three buttons competing with the one action that matters.
                    IconButton(onClick = { actionsOpen = true }) {
                        AppIcon(IconGlyph.MORE, contentDescription = "More actions")
                    }
                }
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
            Card(modifier = Modifier.fillMaxWidth()) {
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(dueText(due), style = MaterialTheme.typography.titleMedium)
                    groupName?.takeIf { it.isNotBlank() }?.let { group ->
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

            // The one action this screen is for, given the whole width.
            Button(
                onClick = onRecordSpray,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Record spray")
            }

            message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            Text("Spray history", style = MaterialTheme.typography.titleSmall)
            if (history.isEmpty()) {
                EmptyState(
                    glyph = IconGlyph.SPRAY,
                    title = "Nothing sprayed yet",
                    body = "Recording a spray is what starts this history: the products, the " +
                        "amounts and the date the traffic light is worked out from.",
                    actionLabel = "Record spray",
                    onAction = onRecordSpray
                )
            }
            history.forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
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
                EmptyState(
                    glyph = IconGlyph.RECORDINGS,
                    title = "No recording for this asset",
                    body = "Recording while spraying leaves the evidence of what was actually " +
                        "driven, and the coverage that goes with it."
                )
            }
            recordings.forEach { session ->
                Card(modifier = Modifier.fillMaxWidth(), onClick = { onOpenRecording(session.id) }) {
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
            title = { Text("Delete this asset?") },
            text = { Text("Its spray history will be deleted with it. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.delete()
                    onBack()
                }, colors = destructiveTextButtonColors()) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            }
        )
    }

    // The secondary actions, in a sheet. The asset is re-read from the database when it is
    // opened rather than copied once, so a rename made from the edit screen cannot be saved
    // back here as a stale name.
    if (actionsOpen) {
        ModalBottomSheet(onDismissRequest = { actionsOpen = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    modifier = Modifier.clickable {
                        actionsOpen = false
                        onEdit()
                    },
                    headlineContent = { Text("Edit details") },
                    supportingContent = {
                        Text("Name, block, kind, interval, boom width and notes")
                    },
                    leadingContent = { AppIcon(IconGlyph.EDIT) }
                )
                ListItem(
                    modifier = Modifier.clickable {
                        actionsOpen = false
                        exportLauncher.launch("${track?.name ?: "asset"}.gpx")
                    },
                    headlineContent = { Text("Export GPX") },
                    supportingContent = { Text("The line on its own, for another device") },
                    leadingContent = { AppIcon(IconGlyph.EXPORT) }
                )
                ListItem(
                    modifier = Modifier.clickable {
                        actionsOpen = false
                        confirmingDelete = true
                    },
                    headlineContent = {
                        // The irreversible one, in the colour that says so rather than in the
                        // same green as Save.
                        Text("Delete this asset", color = MaterialTheme.colorScheme.error)
                    },
                    supportingContent = { Text("Its spray history goes with it") },
                    leadingContent = {
                        AppIcon(glyph = IconGlyph.TRASH, tint = MaterialTheme.colorScheme.error)
                    }
                )
            }
        }
    }
}

/** Operator wording for the traffic light. */
internal fun dueText(due: DueInfo?): String =
    due?.let { DuePhrase.of(it.status, it.daysUntilDue) } ?: "Checking due date"
