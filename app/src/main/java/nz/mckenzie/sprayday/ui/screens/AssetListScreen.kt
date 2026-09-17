package nz.mckenzie.sprayday.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
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
    onOpenTab: (Tab) -> Unit = {},
    onOpenAsset: (Long) -> Unit,
    onDrawAsset: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val tracks by viewModel.assetsWithDue.collectAsStateWithLifecycle()
    val expanded by viewModel.expandedBlocks.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    // Folded once per change rather than per frame: this is arithmetic over every asset and
    // has no business running on each recomposition.
    val rows = remember(tracks, expanded) { AssetListRows.build(tracks, expanded) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importGpx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assets") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        AppIcon(IconGlyph.SETTINGS, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = { SprayDayNavBar(current = Tab.ASSETS, onSelect = onOpenTab) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Two ways to make an asset, and only two: recording, the recordings list and
            // settings used to be buttons here, and are now the tabs in the bar below and
            // the icon in the bar above. Drawing gets the filled treatment because it is
            // what happens standing in the paddock, which is the commoner case.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onDrawAsset) { Text("Draw") }
                OutlinedButton(
                    onClick = { importLauncher.launch(GPX_MIME_TYPES) },
                    enabled = !busy
                ) {
                    Text("Import GPX")
                }
            }

            message?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }

            if (tracks.isEmpty()) {
                EmptyState(
                    glyph = IconGlyph.ASSETS,
                    title = "No assets yet",
                    body = "Import a GPX file, or draw one on the map. Every asset then keeps " +
                        "its own spray history.",
                    actionLabel = "Draw one",
                    onAction = onDrawAsset
                )
            } else {
                // Rows rather than a card each: a list of assets is a list, and a card per
                // row made five paddocks look like five unrelated things. A block is the one
                // thing that does get a heading, because it is a heading over other rows.
                LazyColumn {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        if (index > 0) HorizontalDivider()
                        when (row) {
                            is AssetListRow.Block -> BlockRow(
                                row = row,
                                onToggle = { viewModel.toggleBlock(row.name) }
                            )

                            is AssetListRow.Asset -> AssetRow(
                                item = row.item,
                                inBlock = row.item.groupName?.isNotBlank() == true,
                                onOpen = { onOpenAsset(row.item.asset.id) },
                                onDelete = { viewModel.delete(row.item.asset.id) }
                            )
                        }
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
private fun AssetRow(
    item: AssetWithDue,
    inBlock: Boolean = false,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = AssetShape.fromStorage(item.asset.shape)
    ListItem(
        // An asset inside a block is stepped in, so the list reads as what it is: a tile
        // with rows under it, rather than a run of rows that happen to be adjacent.
        modifier = Modifier
            .clickable(onClick = onOpen)
            .padding(start = if (inBlock) 16.dp else 0.dp),
        headlineContent = {
            Text(item.asset.name, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = { Text(assetRowDetail(item)) },
        // What it is, then when it is due: the icon is read first, the colour of the dot
        // second, which is the order the operator asked the questions in.
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssetKindIcon(kind = AssetKind.fromStorage(item.asset.kind), shape = shape)
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(14.dp)
                        .background(
                            parseHexColor(AssetColors.forStatus(item.due.status)),
                            CircleShape
                        )
                )
            }
        },
        trailingContent = { DestructiveTextButton(text = "Delete", onClick = onDelete) }
    )
}

/**
 * A block, collapsed: what it is, how much of it is left to do, and how urgent that is.
 *
 * The tile is the whole point of folding the list: a block of five lines is one thing to
 * look at, and the only questions about it that are not answered by the map are how big and
 * how much is left. The dot is the traffic light - red for the most urgent asset in the
 * block, never an average, because one overdue line is not made up for by four that are not
 * due - and the bar is the work done, in the same colour, so the bar and the dot agree.
 *
 * Tapping anywhere opens it. The chevron is a hint rather than the target, which is what
 * makes this usable with gloves on.
 */
@Composable
private fun BlockRow(row: AssetListRow.Block, onToggle: () -> Unit) {
    val totals = row.totals
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        headlineContent = {
            Text(row.name, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(blockSizeLine(totals), style = MaterialTheme.typography.bodySmall)
                Text(blockWorkLine(totals), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { 1f - totals.leftFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = parseHexColor(AssetColors.forStatus(totals.status)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    // The bar ends where the block's work ends; Material's little stop dot
                    // would sit at the far right as though something else were still coming.
                    drawStopIndicator = {}
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(
                        parseHexColor(AssetColors.forStatus(totals.status)),
                        CircleShape
                    )
            )
        },
        trailingContent = {
            AppIcon(
                glyph = IconGlyph.CHEVRON,
                modifier = Modifier.rotate(if (row.expanded) 90f else 0f),
                contentDescription = if (row.expanded) "Close the block" else "Open the block"
            )
        }
    )
}

/**
 * What the row says under an asset's name.
 *
 * A spot has no length, and a line that has not been drawn on yet has none either, so
 * in both cases the length is left out rather than printed as "0 m": the row then says
 * the thing the operator is actually looking for, which is when it is due.
 */
internal fun assetRowDetail(item: AssetWithDue): String {
    val length = item.asset.lengthM
    val drawnLine = AssetShape.fromStorage(item.asset.shape) == AssetShape.LINE && length > 0.0
    return if (drawnLine) "${formatDistance(length)} \u00b7 ${dueLabel(item)}" else dueLabel(item)
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
