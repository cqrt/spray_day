package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.map.BasemapView
import nz.mckenzie.sprayday.ui.formatArea
import nz.mckenzie.sprayday.ui.formatDistance
import nz.mckenzie.sprayday.viewmodel.DrawAssetViewModel

/**
 * Draws a track by tapping the basemap, showing the running length so an
 * operator can sanity-check the line before saving it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawAssetScreen(viewModel: DrawAssetViewModel, onBack: () -> Unit) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val basemap by viewModel.basemap.collectAsStateWithLifecycle()
    val pointCount by viewModel.pointCount.collectAsStateWithLifecycle()
    val sideTrackCount by viewModel.sideTrackCount.collectAsStateWithLifecycle()
    val drawingSideTrack by viewModel.drawingSideTrack.collectAsStateWithLifecycle()
    val junctionPicked by viewModel.junctionPicked.collectAsStateWithLifecycle()
    val lengthM by viewModel.lengthM.collectAsStateWithLifecycle()
    val groundSqm by viewModel.groundSqm.collectAsStateWithLifecycle()
    val geoJson by viewModel.draftGeoJson.collectAsStateWithLifecycle()
    val initialFrame by viewModel.initialFrame.collectAsStateWithLifecycle()
    val canSave by viewModel.canSave.collectAsStateWithLifecycle()
    val savedAssetId by viewModel.savedAssetId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val shape by viewModel.shape.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val trackName by viewModel.trackName.collectAsStateWithLifecycle()

    val isSpot = shape == AssetShape.POINT

    // Ground with an edge: drawn as corners, closed by the app when it is saved, and with no side
    // tracks - a ring has no line for one to hang off.
    val isGround = shape == AssetShape.AREA

    var naming by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }

    LaunchedEffect(savedAssetId) {
        if (savedAssetId != null) {
            // Consume before navigating: the signal must not survive this screen.
            viewModel.consumeSaveResult()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            editing -> trackName ?: AssetPhrase.changeLabel(shape)
                            isSpot -> "Add a spot"
                            isGround -> "Draw the boundary"
                            else -> "Draw a line"
                        }
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(IconGlyph.BACK, contentDescription = "Back") } }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            BasemapView(
                basemap = basemap,
                apiKey = apiKey,
                assetGeoJson = geoJson,
                // Drawing happens where the operator is standing, so that is the frame.
                fitBounds = initialFrame,
                onMapClick = { latitude, longitude, radiusM, onTheLineRadiusM ->
                    viewModel.addPoint(latitude, longitude, radiusM, onTheLineRadiusM)
                },
                modifier = Modifier.fillMaxSize()
            )

            AttributionStrip(
                basemap = basemap,
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
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // What it is, and whether it is a line or a spot, belong to the details form while
                    // a track that already exists is being changed: this screen moves ground, and a
                    // picker here would be a second way to change the same field.
                    if (!editing) {
                        ChoiceRow(
                            label = "What it is",
                            choices = AssetPhrase.kinds,
                            selected = kind,
                            // The kind decides whether this is a line to tap out or a place to tap
                            // once, so there is no second question about it here.
                            onChoose = viewModel::chooseKind,
                            text = AssetPhrase::kind
                        )
                    }
                    Text(
                        text = if (isSpot) {
                            if (pointCount == 0) "No spot yet" else "Spot placed"
                        } else {
                            val track = "$pointCount points \u00b7 ${formatDistance(lengthM)}" +
                                (groundSqm?.takeIf { it > 0.0 }
                                    ?.let { " \u00b7 ${formatArea(it)} of ground" } ?: "")
                            when (sideTrackCount) {
                                0 -> track
                                1 -> "$track \u00b7 1 side track"
                                else -> "$track \u00b7 $sideTrackCount side tracks"
                            }
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = message ?: when {
                            // Ground with an edge first: the corners are tapped out and the app closes
                            // the ring itself, so what the bar says is about a boundary rather than a
                            // line - and saying it before the empty-drawing messages keeps a carpark
                            // from being told to "add points" when what it wants is three corners.
                            isGround && pointCount < 3 ->
                                "Tap the corners of the car park - three at least."
                            isGround ->
                                "Tap any more corners, then save. The last one joins the first."
                            isSpot -> "Tap the map where it is."
                            drawingSideTrack -> "Tap along the side track, then press \u201cBack to the track\u201d."
                            editing && pointCount == 0 -> "No line on this track yet - tap the map to draw one."
                            pointCount == 0 -> "Tap the map to add points."
                            junctionPicked -> "A side track will leave the track here. Press \u201cSide track\u201d, " +
                                "or tap the track somewhere else to move it."
                            editing -> "Tap to add a point, or tap the track where a side track should leave it."
                            else -> "Keep tapping to extend the line."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::undo,
                            enabled = pointCount > 0
                        ) { Text("Undo") }
                        OutlinedButton(
                            onClick = viewModel::clear,
                            enabled = pointCount > 0
                        ) { Text("Clear") }
                        // Changing a track that exists has a name already, so Save is the whole of it:
                        // the dialog is for naming a new one, and it has nothing to ask here.
                        if (editing) {
                            Button(onClick = viewModel::saveChanges, enabled = canSave) {
                                Text("Save changes")
                            }
                        } else {
                            Button(onClick = { naming = true }, enabled = canSave) { Text("Save") }
                        }
                    }

                    // A track with a side track off it: the side track leaves the line where the line
                    // currently ends, so the junction is a vertex of both and the join is exact. A ring
                    // has no such end and no side tracks: it is one boundary round one piece of ground.
                    if (!isSpot && !isGround) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (drawingSideTrack) {
                                OutlinedButton(onClick = viewModel::backToTheLine) {
                                    Text("Back to the track")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = viewModel::startSideTrack,
                                    enabled = pointCount >= 2
                                ) { Text("Side track") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = {
                Text(
                    when {
                        isSpot -> "Name this spot"
                        isGround -> "Name this carpark"
                        else -> "Name this line"
                    }
                )
            },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = false
                        viewModel.save(draftName)
                    },
                    enabled = draftName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Cancel") }
            }
        )
    }
}
