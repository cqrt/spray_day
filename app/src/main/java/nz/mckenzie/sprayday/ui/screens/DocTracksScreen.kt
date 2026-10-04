package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.viewmodel.DocTracksViewModel

/**
 * The DOC Tracks browser: DOC's track network searched at the source, and the ones you work imported.
 *
 * DOC publishes its tracks as a hosted service rather than a file, so this screen asks that service
 * for a page of tracks - by name, by where the phone is, or both - and imports the ticked ones as
 * ordinary assets. It is a deliberate detour from the fence line, which is why it is a screen of its
 * own and not part of the map: you come here to bring tracks in, then go back to the work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocTracksScreen(
    viewModel: DocTracksViewModel,
    onBack: () -> Unit
) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val nearMe by viewModel.nearMe.collectAsStateWithLifecycle()
    val radiusKm by viewModel.radiusKm.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val searched by viewModel.searched.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DOC tracks") },
                navigationIcon = {
                    IconButton(onClick = onBack) { AppIcon(IconGlyph.BACK, contentDescription = "Back") }
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
                text = "Search the Department of Conservation's track network and import the tracks " +
                    "you work. Nothing is written until you import.",
                style = MaterialTheme.typography.bodySmall
            )

            OutlinedTextField(
                value = name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Track name contains") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    viewModel.search()
                }),
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = nearMe, onCheckedChange = viewModel::onNearMeChange)
                Text("Near me")
                if (nearMe) {
                    OutlinedTextField(
                        value = radiusKm,
                        onValueChange = viewModel::onRadiusChange,
                        label = { Text("km") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            focus.clearFocus()
                            viewModel.search()
                        }),
                        modifier = Modifier
                            .width(110.dp)
                            .padding(start = 8.dp)
                    )
                }
            }

            Button(
                onClick = {
                    focus.clearFocus()
                    viewModel.search()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Search")
            }

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            if (selected.isNotEmpty()) {
                Button(
                    onClick = viewModel::importSelected,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import ${selected.size} selected")
                }
            }

            if (results.isEmpty() && searched && message == null && !busy) {
                EmptyState(
                    glyph = IconGlyph.ASSETS,
                    title = "No tracks matched",
                    body = "Try part of a track's name, or turn on Near me and search again.",
                    actionLabel = "Search again",
                    onAction = { viewModel.search() }
                )
            } else {
                LazyColumn {
                    itemsIndexed(results, key = { _, track -> track.objectId }) { index, track ->
                        if (index > 0) HorizontalDivider()
                        DocTrackRow(
                            track = track,
                            checked = track.objectId in selected,
                            imported = track.sourceRef in imported,
                            onToggle = { viewModel.toggle(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocTrackRow(track: DocTrack, checked: Boolean, imported: Boolean, onToggle: () -> Unit) {
    ListItem(
        // A track already on the phone is not something to tick: the box and the row are both off, and
        // the line says why rather than leaving the operator to wonder.
        modifier = Modifier.clickable(enabled = !imported, onClick = onToggle),
        leadingContent = {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = !imported)
        },
        headlineContent = { Text(track.name, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(
                listOfNotNull(
                    track.kind,
                    "${track.pointCount} points",
                    formatDistance(track.lengthM),
                    if (imported) "already imported" else null
                ).joinToString(" \u00b7 ")
            )
        }
    )
}
