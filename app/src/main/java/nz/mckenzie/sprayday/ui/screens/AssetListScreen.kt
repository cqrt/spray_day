package nz.mckenzie.sprayday.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.viewmodel.AssetListViewModel

/**
 * The track library: what tracks exist, how long they are, and when each is next
 * due. Tracks can be imported from GPX or drawn on the map.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssetListScreen(
    viewModel: AssetListViewModel,
    onBack: () -> Unit,
    onOpenAsset: (Long) -> Unit,
    onDrawAsset: () -> Unit,
    onRecordAsset: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val tracks by viewModel.assetsWithDue.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importGpx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assets") },
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
            // These four actions do not fit across a phone as one row: the last one
            // was squeezed to a blank sliver against the right edge, which is how the
            // recordings page went missing. A wrapping row keeps every label readable
            // at any width, and the primary action - recording a track - is the one
            // that gets the filled treatment, not the rarest.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onRecordAsset) { Text("Record") }
                OutlinedButton(onClick = onDrawAsset) { Text("Draw") }
                OutlinedButton(
                    onClick = { importLauncher.launch(GPX_MIME_TYPES) },
                    enabled = !busy
                ) {
                    Text("Import GPX")
                }
                OutlinedButton(onClick = onOpenRecordings) { Text("Recordings") }
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }

            message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }

            if (tracks.isEmpty()) {
                Text(
                    text = "No assets yet. Import a GPX file, or draw one on the map.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tracks, key = { it.asset.id }) { item ->
                        AssetRow(
                            item = item,
                            onOpen = { onOpenAsset(item.asset.id) },
                            onDelete = { viewModel.delete(item.asset.id) }
                        )
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
private fun AssetRow(item: AssetWithDue, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onOpen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // What it is, then when it is due: the icon is read first, the colour of the
            // dot second, which is the order the operator asked the questions in.
            AssetKindIcon(kind = AssetKind.fromStorage(item.asset.kind))
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(14.dp)
                    .background(parseHexColor(AssetColors.forStatus(item.due.status)), CircleShape)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(item.asset.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${formatDistance(item.asset.lengthM)} \u00b7 ${dueLabel(item)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/** Operator wording for the traffic light, so the colour is never a mystery. */
internal fun dueLabel(item: AssetWithDue): String = when (item.due.status) {
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
