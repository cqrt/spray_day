package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.viewmodel.BlockRow
import nz.mckenzie.sprayday.viewmodel.BlocksViewModel

/**
 * The blocks, and what each of them holds.
 *
 * A screen of its own rather than a corner of Settings, because a block is the operator's
 * working arrangement rather than an application preference: it is opened from the assets list,
 * which is where the blocks are read.
 *
 * Blocks are made on an asset and taken apart here, which is the one thing that makes this
 * screen worth having - renaming is how a misspelling is put right, and until now the only way
 * to change a block name was to type it again on every asset in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlocksScreen(
    viewModel: BlocksViewModel,
    onBack: () -> Unit,
    onOpenBlock: (Long) -> Unit
) {
    val blocks by viewModel.blocks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blocks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        AppIcon(IconGlyph.BACK, contentDescription = "Back")
                    }
                }
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
                text = "Assets sharing a block are worked together, and fold into one tile in " +
                    "the asset list. Renaming or deleting a block never touches the assets in it.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (blocks.isEmpty()) {
                EmptyState(
                    glyph = IconGlyph.BLOCKS,
                    title = "No blocks yet",
                    body = "A block is started by naming it on an asset: open an asset, tap Edit, " +
                        "and type a name under \"Block or group\". Assets sharing one are worked " +
                        "together, and fold into a single tile in the list.",
                    actionLabel = "Back to assets",
                    onAction = onBack
                )
            } else {
                LazyColumn {
                    itemsIndexed(blocks, key = { _, row -> row.id }) { index, row ->
                        if (index > 0) HorizontalDivider()
                        BlockListRow(row = row, onOpen = { onOpenBlock(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockListRow(row: BlockRow, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(row.name, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                row.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                blockListLines(row).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        // The same dot the asset list uses: the most urgent asset in the block. A block with
        // nothing in it has no state to show, and says so in words instead.
        leadingContent = {
            if (!row.isEmpty) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(
                            parseHexColor(AssetColors.forStatus(row.totals.status)),
                            CircleShape
                        )
                )
            }
        },
        trailingContent = {
            AppIcon(glyph = IconGlyph.CHEVRON, contentDescription = "Rename or delete the block")
        }
    )
}

/**
 * What a block's row says, line by line.
 *
 * The same two lines the collapsed tile in the asset list carries, plus the one case that only
 * exists here: a block with nothing in it, which is a leftover rather than a mistake and is
 * worth saying plainly so the way out of it is obvious.
 */
internal fun blockListLines(row: BlockRow): List<String> =
    if (row.isEmpty) {
        listOf("No assets in it yet")
    } else {
        listOf(blockSizeLine(row.totals), blockWorkLine(row.totals))
    }
