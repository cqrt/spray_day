package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.doc.DocPlaces
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.DocTrackSort
import nz.mckenzie.sprayday.domain.doc.distanceM
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.viewmodel.DocTracksViewModel

/**
 * The DOC Tracks browser: DOC's track network searched at the source, and the ones you work imported.
 *
 * DOC publishes its tracks as a hosted service rather than a file, so this screen asks that service
 * for a page of tracks - by name, by where the phone is, or both - and imports the ticked ones as
 * ordinary assets. It is a deliberate detour from the fence line, which is why it is a screen of its
 * own and not part of the map: you come here to bring tracks in, then go back to the work.
 *
 * The chrome is deliberately thin - two rows of search, a Filter and a Sort - because the list is what
 * the operator is here for, and it takes the rest of the screen. The filters are labelled menus rather
 * than a row of sliding chips, which said nothing about what they filtered and scrolled the list away.
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
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val kindChoices by viewModel.kindChoices.collectAsStateWithLifecycle()
    val kinds by viewModel.kinds.collectAsStateWithLifecycle()
    val typeChoices by viewModel.typeChoices.collectAsStateWithLifecycle()
    val types by viewModel.types.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val phoneFix by viewModel.phoneFix.collectAsStateWithLifecycle()
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        focus.clearFocus()
                        viewModel.search()
                    },
                    enabled = !busy
                ) {
                    Text("Search")
                }
            }

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

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

            // What a search found, narrowed and ordered here rather than by asking DOC again: the
            // filters are the ones actually on the page, and the nearest order re-runs as the phone moves.
            if (results.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterMenu(
                        kindChoices = kindChoices,
                        kinds = kinds,
                        typeChoices = typeChoices,
                        types = types,
                        onToggleKind = viewModel::toggleKind,
                        onToggleType = viewModel::toggleType,
                        onClear = {
                            viewModel.clearKinds()
                            viewModel.clearTypes()
                        }
                    )
                    SortMenu(sort = sort, onSort = viewModel::setSort)
                }
            }

            if (selected.isNotEmpty()) {
                Button(
                    onClick = viewModel::importSelected,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import ${selected.size} selected")
                }
            }

            when {
                results.isEmpty() && searched && message == null && !busy -> EmptyState(
                    glyph = IconGlyph.ASSETS,
                    title = "No tracks matched",
                    body = "Try part of a track's name, or turn on Near me and search again.",
                    actionLabel = "Search again",
                    onAction = { viewModel.search() }
                )

                // A filter that hides every row is not "no tracks matched": the way out is to clear a
                // filter, so say that rather than showing the empty state again.
                results.isNotEmpty() && visible.isEmpty() -> Text(
                    text = "Nothing matches those filters. Clear one to see the rest.",
                    style = MaterialTheme.typography.bodyMedium
                )

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(visible, key = { _, track -> track.objectId }) { index, track ->
                        if (index > 0) HorizontalDivider()
                        DocTrackRow(
                            track = track,
                            checked = track.objectId in selected,
                            imported = track.sourceRef in imported,
                            distanceM = phoneFix?.let { position -> track.distanceM(position) },
                            onToggle = { viewModel.toggle(track) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * The **Filter** control: a labelled menu of the kinds and track types the search returned, each one
 * ticked or not.
 *
 * A menu rather than a row of chips, because the chips scrolled sideways and said nothing about what
 * they filtered - and they were as tall as the list they were filtering. The count on the button is
 * how many filters are on, so a list that looks short says why.
 */
@Composable
private fun FilterMenu(
    kindChoices: List<String>,
    kinds: Set<String>,
    typeChoices: List<String>,
    types: Set<String>,
    onToggleKind: (String) -> Unit,
    onToggleType: (String) -> Unit,
    onClear: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val count = kinds.size + types.size

    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(if (count == 0) "Filter" else "Filter ($count)")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (kindChoices.isNotEmpty()) {
                MenuHeading("Kind")
                kindChoices.forEach { kind ->
                    MenuChoice(kind, kind in kinds) { onToggleKind(kind) }
                }
            }
            if (typeChoices.isNotEmpty()) {
                MenuHeading("Track type")
                typeChoices.forEach { type ->
                    MenuChoice(type, type in types) { onToggleType(type) }
                }
            }
            if (count > 0) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear filters") },
                    onClick = {
                        onClear()
                        open = false
                    }
                )
            }
        }
    }
}

/** The **Sort** control: the order the list is in, and the one it can be in. */
@Composable
private fun SortMenu(sort: DocTrackSort, onSort: (DocTrackSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when (sort) {
        DocTrackSort.NEAREST -> "Sort: Nearest"
        DocTrackSort.NAME -> "Sort: Name"
    }

    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuChoice("Nearest to phone", sort == DocTrackSort.NEAREST) {
                onSort(DocTrackSort.NEAREST)
                open = false
            }
            MenuChoice("Name", sort == DocTrackSort.NAME) {
                onSort(DocTrackSort.NAME)
                open = false
            }
        }
    }
}

/** A small heading inside a menu, naming the group the choices under it belong to. */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/**
 * One choice in a menu: its label, and a tick when it is on.
 *
 * The menu stays open when it is pressed, because filtering is a thing done twice - a kind and then a
 * type - and a menu that closed on the first pick would make the second one a second press of Filter.
 */
@Composable
private fun MenuChoice(label: String, chosen: Boolean, onChoose: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = if (chosen) {
            { AppIcon(glyph = IconGlyph.CHECK, dimension = 18.dp) }
        } else {
            null
        },
        onClick = onChoose
    )
}

@Composable
private fun DocTrackRow(
    track: DocTrack,
    checked: Boolean,
    imported: Boolean,
    distanceM: Double?,
    onToggle: () -> Unit
) {
    ListItem(
        // A track already on the phone is not something to tick: the box and the row are both off, and
        // the line says why rather than leaving the operator to wonder.
        modifier = Modifier.clickable(enabled = !imported, onClick = onToggle),
        leadingContent = {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = !imported)
        },
        headlineContent = {
            // The name the import will give it - the place, then DOC's own name - so a site the app
            // names wrongly is seen here, at a glance, rather than after the track has landed.
            Text(
                DocPlaces.assetName(track.name, track.flocId),
                style = MaterialTheme.typography.titleSmall
            )
        },
        supportingContent = {
            Text(
                listOfNotNull(
                    track.kind,
                    // How the track is walked, in place of a vertex count: "584 points" says nothing
                    // an operator uses, where one-way tells them what they are taking on.
                    track.trackType,
                    formatDistance(track.lengthM),
                    // How far away it is, from the phone's own fix - so "Near me" can be seen to be
                    // the radius it says it is.
                    distanceM?.let { distance -> "${formatDistance(distance)} away" },
                    if (imported) "already imported" else null
                ).joinToString(" \u00b7 ")
            )
        }
    )
}
