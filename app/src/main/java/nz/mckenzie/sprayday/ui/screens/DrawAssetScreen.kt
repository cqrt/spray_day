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
import nz.mckenzie.sprayday.map.LinzMapView
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
    val points by viewModel.points.collectAsStateWithLifecycle()
    val lengthM by viewModel.lengthM.collectAsStateWithLifecycle()
    val geoJson by viewModel.draftGeoJson.collectAsStateWithLifecycle()
    val initialFrame by viewModel.initialFrame.collectAsStateWithLifecycle()
    val canSave by viewModel.canSave.collectAsStateWithLifecycle()
    val savedAssetId by viewModel.savedAssetId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val kind by viewModel.kind.collectAsStateWithLifecycle()
    val shape by viewModel.shape.collectAsStateWithLifecycle()

    val isSpot = shape == AssetShape.POINT

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
                title = { Text(if (isSpot) "Add a spot" else "Draw a line") },
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(IconGlyph.BACK, contentDescription = "Back") } }
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
                assetGeoJson = geoJson,
                // Drawing happens where the operator is standing, so that is the frame.
                fitBounds = initialFrame,
                onMapClick = { latitude, longitude, _ -> viewModel.addPoint(latitude, longitude) },
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
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoiceRow(
                        label = "What it is",
                        choices = AssetPhrase.kinds,
                        selected = kind,
                        onChoose = viewModel::chooseKind,
                        text = AssetPhrase::kind
                    )
                    if (kind == AssetKind.INFRASTRUCTURE) {
                        ChoiceRow(
                            label = "Shape",
                            choices = AssetPhrase.shapes,
                            selected = shape,
                            onChoose = viewModel::chooseShape,
                            text = AssetPhrase::shapeChoice
                        )
                    }
                    Text(
                        text = if (isSpot) {
                            if (points.isEmpty()) "No spot yet" else "Spot placed"
                        } else {
                            "${points.size} points \u00b7 ${formatDistance(lengthM)}"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = message ?: when {
                            isSpot -> "Tap the map where it is."
                            points.isEmpty() -> "Tap the map to add points."
                            else -> "Keep tapping to extend the line."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = viewModel::undo,
                            enabled = points.isNotEmpty()
                        ) { Text("Undo") }
                        OutlinedButton(
                            onClick = viewModel::clear,
                            enabled = points.isNotEmpty()
                        ) { Text("Clear") }
                        Button(onClick = { naming = true }, enabled = canSave) { Text("Save") }
                    }
                }
            }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(if (isSpot) "Name this spot" else "Name this line") },
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
