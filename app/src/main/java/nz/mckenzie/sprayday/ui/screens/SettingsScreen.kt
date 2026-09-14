package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.viewmodel.KeyCheckState
import nz.mckenzie.sprayday.viewmodel.KeySource
import nz.mckenzie.sprayday.viewmodel.SettingsViewModel

/**
 * App settings, which today means the LINZ Basemaps key.
 *
 * Standard-access keys expire every 90 days and the failure is silent, so this screen
 * exists to make a dead key a two-minute fix in the paddock rather than a wait for a
 * new build. [Check] is the important button: it asks LINZ for one tile and reports
 * exactly what came back.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val keyText by viewModel.keyText.collectAsStateWithLifecycle()
    val keySource by viewModel.keySource.collectAsStateWithLifecycle()
    val activeKeyLabel by viewModel.activeKeyLabel.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val storedTiles by viewModel.storedTiles.collectAsStateWithLifecycle()

    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Map") } }
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("LINZ Basemaps key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "In use: " +
                            (activeKeyLabel.ifBlank { "none" }) +
                            " \u2014 " + sourceLabel(keySource),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "The map imagery comes from LINZ and needs one of their keys. " +
                            "Standard-access keys expire every 90 days, and an expired key " +
                            "shows up as imagery that still works where you have already been " +
                            "and is blank somewhere new \u2014 so if the map ever looks wrong, " +
                            "press Check.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = keyText,
                        onValueChange = viewModel::setKeyText,
                        label = { Text("Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Wrapping, because three actions with long labels do not fit across
                    // a phone as one row.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = viewModel::save) { Text("Save") }
                        OutlinedButton(
                            onClick = viewModel::check,
                            enabled = check != KeyCheckState.Checking
                        ) {
                            Text(if (check == KeyCheckState.Checking) "Checking…" else "Check")
                        }
                        OutlinedButton(onClick = viewModel::clearEnteredKey) {
                            Text("Use the built-in key")
                        }
                    }

                    when (val state = check) {
                        KeyCheckState.Idle -> Unit
                        KeyCheckState.Checking ->
                            Text("Asking LINZ for a tile\u2026", style = MaterialTheme.typography.bodySmall)

                        KeyCheckState.Worked -> Text(
                            text = "The key works: LINZ accepted it.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        is KeyCheckState.Failed -> Text(
                            text = "The key did not work: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    message?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

                    TextButton(onClick = { uriHandler.openUri("https://basemaps.linz.govt.nz") }) {
                        Text("Get a key from LINZ")
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("This device", style = MaterialTheme.typography.titleMedium)
                    Text(viewModel.versionLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${storedTiles.tiles} imagery tiles cached, ${storedTiles.sizeLabel}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Downloaded areas, their progress and clearing them are on the " +
                            "Offline areas screen.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun sourceLabel(source: KeySource): String = when (source) {
    KeySource.ENTERED -> "entered on this device"
    KeySource.BUILT_IN -> "built into this build"
    KeySource.NONE -> "no key set yet"
}
