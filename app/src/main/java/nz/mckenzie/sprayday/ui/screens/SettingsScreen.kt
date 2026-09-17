package nz.mckenzie.sprayday.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nz.mckenzie.sprayday.domain.backup.BackupDestination
import nz.mckenzie.sprayday.viewmodel.KeyCheckState
import nz.mckenzie.sprayday.viewmodel.KeySource
import nz.mckenzie.sprayday.viewmodel.InstallState
import nz.mckenzie.sprayday.viewmodel.OffsiteCheckState
import nz.mckenzie.sprayday.viewmodel.SettingsViewModel
import nz.mckenzie.sprayday.viewmodel.UpdateState
import nz.mckenzie.sprayday.viewmodel.UpdateViewModel

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
fun SettingsScreen(
    viewModel: SettingsViewModel,
    updateViewModel: UpdateViewModel,
    onBack: () -> Unit
) {
    val keyText by viewModel.keyText.collectAsStateWithLifecycle()
    val keySource by viewModel.keySource.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val installState by updateViewModel.install.collectAsStateWithLifecycle()
    val autoUpdateChecks by updateViewModel.autoCheckEnabled.collectAsStateWithLifecycle()
    // Android's permission to install this is granted on a system screen this app sends
    // the operator to, so it is state rather than a value read once: a plain read leaves
    // the warning on screen after they have already granted it.
    var canInstallUpdates by remember { mutableStateOf(updateViewModel.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstallUpdates = updateViewModel.canInstall()
        onPauseOrDispose { }
    }
    val activeKeyLabel by viewModel.activeKeyLabel.collectAsStateWithLifecycle()
    val check by viewModel.check.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val storedTiles by viewModel.storedTiles.collectAsStateWithLifecycle()
    val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val reminderOutcome by viewModel.reminderOutcome.collectAsStateWithLifecycle()
    val checkingReminders by viewModel.checkingReminders.collectAsStateWithLifecycle()
    val dataMessage by viewModel.dataMessage.collectAsStateWithLifecycle()
    val dataBusy by viewModel.dataBusy.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pendingRestore.collectAsStateWithLifecycle()
    val offsiteMessage by viewModel.offsiteMessage.collectAsStateWithLifecycle()
    val offsiteBusy by viewModel.offsiteBusy.collectAsStateWithLifecycle()
    val offsiteCheck by viewModel.offsiteCheck.collectAsStateWithLifecycle()
    val copiesToChoose by viewModel.copiesToChoose.collectAsStateWithLifecycle()
    val backupDestination by viewModel.backupDestination.collectAsStateWithLifecycle()
    val backupFileName by viewModel.backupFileName.collectAsStateWithLifecycle()
    val backUpAutomatically by viewModel.backUpAutomatically.collectAsStateWithLifecycle()
    val lastBackupLabel by viewModel.lastBackupLabel.collectAsStateWithLifecycle()
    val repoText by viewModel.repoText.collectAsStateWithLifecycle()
    val tokenText by viewModel.tokenText.collectAsStateWithLifecycle()

    // The file the off-site copy is written to. Created through the same picker as the
    // manual backup, so it can be anywhere the operator can reach: a Downloads folder, a
    // stick, or a folder another app syncs.
    val offsiteFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::chooseOffsiteFile) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportBackupTo) }

    // Any MIME type: a backup can end up on a stick or in an email, and being unable
    // to select the file is worse than a lax filter.
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::chooseBackupToRestore) }

    val handoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let(viewModel::exportHandoverTo) }

    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsAllowed = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Returning from system settings is the other way this can change, so re-read it
    // whenever the screen comes back to the front.
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    // Restoring replaces everything, so the numbers on both sides are shown first. The
    // off-site path asks through this same dialog, because it is the same decision - and a
    // second dialog with its own wording is how two of them end up disagreeing.
    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "${pending.heldBy} holds ${pending.file.describe()}." +
                        (pending.provenance()?.let { age -> "\n\n$age" } ?: "") +
                        "\n\nRestoring replaces what is in the app now " +
                        "(${pending.current.describe()}). This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore) { Text("Cancel") }
            }
        )
    }

    // More than one phone has backed up to the same place, so the operator says which one.
    // The ordinary case is a single copy and never reaches this.
    copiesToChoose?.let { copies ->
        AlertDialog(
            onDismissRequest = viewModel::cancelOffsiteChoice,
            title = { Text("Restore which copy?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "There are ${copies.size} copies. The name says which phone wrote each " +
                            "one, and the next screen says what is in it."
                    )
                    copies.forEach { copy ->
                        OutlinedButton(
                            onClick = { viewModel.chooseOffsiteCopy(copy) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${copy.name} \u2014 ${copy.sizeLabel}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::cancelOffsiteChoice) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { AppIcon(IconGlyph.BACK, contentDescription = "Back") } }
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Reminders", style = MaterialTheme.typography.titleMedium)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(
                            checked = remindersEnabled,
                            onCheckedChange = viewModel::setRemindersEnabled
                        )
                        Text(
                            text = "Tell me when assets are due",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Text(
                        text = "Assets are checked twice a day. You are told when one first " +
                            "becomes due, again if it goes from due soon to overdue, and after " +
                            "that at most once a week while it stays due.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (!notificationsAllowed) {
                        Text(
                            text = "Android needs permission before any reminder can appear, " +
                                "so nothing will be posted until this is allowed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            ) {
                                Text("Allow notifications")
                            }
                            TextButton(onClick = { openNotificationSettings(context) }) {
                                Text("Notification settings")
                            }
                        }
                    }

                    reminderOutcome?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

                    OutlinedButton(
                        onClick = viewModel::checkRemindersNow,
                        enabled = !checkingReminders
                    ) {
                        Text(if (checkingReminders) "Checking…" else "Check now")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Updates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "This is version ${updateViewModel.currentLabel}. Spray Day is not " +
                            "installed from an app store, so nothing else will ever mention a new " +
                            "version \u2014 it asks GitHub once a day instead.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(
                            checked = autoUpdateChecks,
                            onCheckedChange = updateViewModel::setAutoCheck
                        )
                        Text(
                            text = "Tell me when a newer version is released",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (!canInstallUpdates) {
                        Text(
                            text = "Android needs your permission before Spray Day can install an " +
                                "update. Downloading works without it; installing does not.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = updateViewModel::openInstallPermission) {
                            Text("Allow installing updates")
                        }
                    }

                    when (val state = updateState) {
                        UpdateState.Idle -> Unit
                        UpdateState.Checking -> Text(
                            text = "Asking GitHub\u2026",
                            style = MaterialTheme.typography.bodySmall
                        )

                        is UpdateState.NothingToInstall -> Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        is UpdateState.Available -> Text(
                            text = "${state.update.title} is available (${state.update.sizeLabel}).",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    when (val state = installState) {
                        InstallState.Idle -> Unit

                        is InstallState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Downloading\u2026 ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        InstallState.HandedToInstaller -> Text(
                            text = "Android is installing it. Confirm the update when it asks, and " +
                                "Spray Day reopens on the new version \u2014 with everything you " +
                                "have recorded kept.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        is InstallState.Failed -> Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = updateViewModel::check,
                            enabled = updateState != UpdateState.Checking
                        ) {
                            Text(
                                if (updateState == UpdateState.Checking) {
                                    "Checking\u2026"
                                } else {
                                    "Check for updates"
                                }
                            )
                        }

                        (updateState as? UpdateState.Available)?.let { state ->
                            Button(
                                onClick = { updateViewModel.install(state.update) },
                                enabled = canInstallUpdates && installState !is InstallState.Downloading
                            ) {
                                Text("Update to ${state.update.version}")
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Your data", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Everything the app holds: assets and their lines, every spray " +
                            "with its amounts, products, and GPS recordings.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { exportLauncher.launch(viewModel.backupFileName()) },
                            enabled = !dataBusy
                        ) {
                            Text("Back up everything")
                        }
                        OutlinedButton(
                            onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                            enabled = !dataBusy
                        ) {
                            Text("Restore from a backup")
                        }
                        OutlinedButton(
                            onClick = { handoverLauncher.launch(viewModel.handoverFileName()) },
                            enabled = !dataBusy
                        ) {
                            Text("Handover record (CSV)")
                        }
                    }
                    dataMessage?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        text = "Downloaded offline imagery is not in the backup: it describes " +
                            "tiles on this phone and can be downloaded again.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "The handover record is one row per product per spray: what " +
                            "went where, when, and which recording proves it. It opens in a " +
                            "spreadsheet \u2014 for a client, an auditor, or whoever takes over.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Off-site copy", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "The backup above is a file you keep. This is the same backup, " +
                            "written by the app itself, so there is a copy somewhere else if " +
                            "this phone is lost, wiped, or dropped in the creek.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    ChoiceRow(
                        label = "Where the copy goes",
                        choices = BackupDestination.entries,
                        selected = backupDestination,
                        onChoose = viewModel::setBackupDestination,
                        text = ::destinationLabel
                    )

                    when (backupDestination) {
                        BackupDestination.OFF -> Text(
                            text = "Nothing is written anywhere on its own. Backing up is the " +
                                "button on the card above, which writes a file you choose.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        BackupDestination.FILE -> {
                            Text(
                                text = if (backupFileName.isBlank()) {
                                    "No file chosen yet."
                                } else {
                                    "Copies are written to $backupFileName, replacing the last " +
                                        "one. The file can be in any folder you can reach, " +
                                        "including one another app syncs."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(
                                onClick = { offsiteFileLauncher.launch(viewModel.backupFileName()) },
                                enabled = !offsiteBusy
                            ) {
                                Text(
                                    if (backupFileName.isBlank()) {
                                        "Choose a file"
                                    } else {
                                        "Choose another file"
                                    }
                                )
                            }
                        }

                        BackupDestination.GITHUB -> {
                            Text(
                                text = "A private repository keeps the copy off the property " +
                                    "and keeps its history: every backup is a commit, so an " +
                                    "older copy is still there if the newest one is wrong.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = repoText,
                                onValueChange = viewModel::setRepoText,
                                label = { Text("Repository, owner/name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = tokenText,
                                onValueChange = viewModel::setTokenText,
                                label = { Text("Token for that repository") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = viewModel::saveOffsite) { Text("Save") }
                                OutlinedButton(
                                    onClick = viewModel::testOffsite,
                                    enabled = offsiteCheck != OffsiteCheckState.Checking
                                ) {
                                    Text(
                                        if (offsiteCheck == OffsiteCheckState.Checking) {
                                            "Testing\u2026"
                                        } else {
                                            "Test"
                                        }
                                    )
                                }
                            }

                            when (val state = offsiteCheck) {
                                OffsiteCheckState.Idle -> Unit

                                OffsiteCheckState.Checking ->
                                    Text("Asking GitHub\u2026", style = MaterialTheme.typography.bodySmall)

                                is OffsiteCheckState.Worked -> Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = state.note,
                                        color = if (state.warning == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    state.warning?.let { warning ->
                                        Text(
                                            text = warning,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }

                                is OffsiteCheckState.Failed -> Text(
                                    text = "The destination did not answer: ${state.message}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Text(
                                text = "The token is a GitHub fine-grained token with Contents: " +
                                    "read and write on this one repository. It is kept on this " +
                                    "phone and never written into a backup \u2014 the copy " +
                                    "lives in the very repository the token can write to.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    if (backupDestination != BackupDestination.OFF) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(
                                checked = backUpAutomatically,
                                onCheckedChange = viewModel::setBackUpAutomatically
                            )
                            Text("Back up once a week, on its own", style = MaterialTheme.typography.bodyMedium)
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = viewModel::backUpOffsiteNow, enabled = !offsiteBusy) {
                                Text(if (offsiteBusy) "Working\u2026" else "Back up now")
                            }
                            OutlinedButton(
                                onClick = viewModel::reviewOffsiteRestore,
                                enabled = !offsiteBusy
                            ) {
                                Text("Restore from the copy")
                            }
                        }
                    }

                    lastBackupLabel?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                    offsiteMessage?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }

                    Text(
                        text = "An empty database is never written over the copy: if this phone " +
                            "holds nothing \u2014 after a wipe, say \u2014 the copy is left " +
                            "alone, so it is still there to restore from.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
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

/**
 * Android's own per-app notification settings, which is where a prompt that will not
 * appear again gets undone.
 */
private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun sourceLabel(source: KeySource): String = when (source) {
    KeySource.ENTERED -> "entered on this device"
    KeySource.BUILT_IN -> "built into this build"
    KeySource.NONE -> "no key set yet"
}

private fun destinationLabel(destination: BackupDestination): String = when (destination) {
    BackupDestination.OFF -> "Nowhere"
    BackupDestination.FILE -> "A file"
    BackupDestination.GITHUB -> "GitHub"
}
