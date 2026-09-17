package nz.mckenzie.sprayday

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.sprayday.ui.screens.DrawAssetScreen
import nz.mckenzie.sprayday.ui.screens.MapScreen
import nz.mckenzie.sprayday.ui.screens.OfflineAreaPickerScreen
import nz.mckenzie.sprayday.ui.screens.OfflineScreen
import nz.mckenzie.sprayday.ui.screens.RecordScreen
import nz.mckenzie.sprayday.ui.screens.RecordingDetailScreen
import nz.mckenzie.sprayday.ui.screens.RecordingsScreen
import nz.mckenzie.sprayday.ui.screens.SettingsScreen
import nz.mckenzie.sprayday.ui.screens.SprayEntryScreen
import nz.mckenzie.sprayday.ui.screens.Tab
import nz.mckenzie.sprayday.ui.screens.AssetDetailScreen
import nz.mckenzie.sprayday.ui.screens.AssetEditScreen
import nz.mckenzie.sprayday.ui.screens.AssetListScreen
import nz.mckenzie.sprayday.ui.theme.SprayDayTheme
import nz.mckenzie.sprayday.viewmodel.DrawAssetViewModel
import nz.mckenzie.sprayday.viewmodel.MapViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineAreaPickerViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingDetailViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingsViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingViewModel
import nz.mckenzie.sprayday.viewmodel.SettingsViewModel
import nz.mckenzie.sprayday.viewmodel.SprayEntryViewModel
import nz.mckenzie.sprayday.viewmodel.UpdateViewModel
import nz.mckenzie.sprayday.viewmodel.AssetDetailViewModel
import nz.mckenzie.sprayday.viewmodel.AssetListViewModel

/** Destinations for now; swap for a NavHost when routes need arguments. */
private enum class Destination { MAP, ASSETS, DRAW, RECORD, OFFLINE, OFFLINE_PICKER, ASSET_DETAIL, ASSET_EDIT, SPRAY_ENTRY, RECORDINGS, RECORDING_DETAIL, SETTINGS }

class MainActivity : ComponentActivity() {

    /**
     * A screen asked for by whatever started the activity - today, a tap on a due
     * reminder. Held outside the composition so `onNewIntent` can set it too.
     */
    private val requestedDestination = mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedDestination.value = destinationFrom(intent)
        setContent {
            SprayDayTheme {
                var destination by rememberSaveable { mutableStateOf(Destination.MAP) }
                var selectedAssetId by rememberSaveable { mutableStateOf<Long?>(null) }
                var selectedSessionId by rememberSaveable { mutableStateOf<Long?>(null) }

                /**
                 * The recording a spray is being logged from, when the operator started
                 * at a recording rather than at a track. Cleared whenever a spray is
                 * started from the track screen, so a spray is only ever linked to the
                 * recording it was actually logged from.
                 */
                var spraySessionId by rememberSaveable { mutableStateOf<Long?>(null) }

                // Bumped on every visit to the draw screen so it gets its own view
                // model: a shared one would carry the previous visit's draft (and
                // its "just saved" signal) into the next drawing session.
                var drawVisit by rememberSaveable { mutableStateOf(0) }

                // System back always returns to the map rather than leaving the app.
                BackHandler(enabled = destination != Destination.MAP) {
                    destination = Destination.MAP
                }

                // A reminder tap asks for the track list. Done here rather than in the
                // initial value so it also works when the app was already open.
                LaunchedEffect(requestedDestination.value) {
                    requestedDestination.value?.let { wanted ->
                        destination = wanted
                        requestedDestination.value = null
                    }
                }

                when (destination) {
                    Destination.MAP -> {
                        val mapViewModel: MapViewModel = viewModel(
                            factory = MapViewModel.factory(applicationContext)
                        )
                        MapScreen(
                            viewModel = mapViewModel,
                            onOpenTab = { destination = destinationOf(it) },
                            onOpenSettings = { destination = Destination.SETTINGS },
                            onOpenAsset = { assetId ->
                                selectedAssetId = assetId
                                destination = Destination.ASSET_DETAIL
                            }
                        )
                    }

                    Destination.ASSETS -> {
                        val assetViewModel: AssetListViewModel = viewModel(
                            factory = AssetListViewModel.factory(applicationContext)
                        )
                        AssetListScreen(
                            viewModel = assetViewModel,
                            onOpenTab = { destination = destinationOf(it) },
                            onOpenAsset = { assetId ->
                                selectedAssetId = assetId
                                destination = Destination.ASSET_DETAIL
                            },
                            onDrawAsset = {
                                drawVisit++
                                destination = Destination.DRAW
                            },
                            onOpenSettings = { destination = Destination.SETTINGS }
                        )
                    }

                    Destination.ASSET_DETAIL -> {
                        val assetId = selectedAssetId
                        if (assetId == null) {
                            destination = Destination.ASSETS
                        } else {
                            val detailViewModel: AssetDetailViewModel = viewModel(
                                key = "detail-$assetId",
                                factory = AssetDetailViewModel.factory(applicationContext, assetId)
                            )
                            AssetDetailScreen(
                                viewModel = detailViewModel,
                                onBack = { destination = Destination.ASSETS },
                                onRecordSpray = {
                                    spraySessionId = null
                                    destination = Destination.SPRAY_ENTRY
                                },
                                onEdit = { destination = Destination.ASSET_EDIT },
                                onOpenRecording = { sessionId ->
                                    selectedSessionId = sessionId
                                    destination = Destination.RECORDING_DETAIL
                                }
                            )
                        }
                    }

                    // Editing is a screen rather than a dialog, and it asks for the same view
                    // model the screen behind it uses: that one is keyed by asset id, so the
                    // form opens on the asset that was just on screen, and saving updates it.
                    Destination.ASSET_EDIT -> {
                        val assetId = selectedAssetId
                        if (assetId == null) {
                            destination = Destination.ASSETS
                        } else {
                            val editViewModel: AssetDetailViewModel = viewModel(
                                key = "detail-$assetId",
                                factory = AssetDetailViewModel.factory(applicationContext, assetId)
                            )
                            AssetEditScreen(
                                viewModel = editViewModel,
                                onDone = { destination = Destination.ASSET_DETAIL }
                            )
                        }
                    }

                    Destination.SPRAY_ENTRY -> {
                        val assetId = selectedAssetId
                        val linkedSessionId = spraySessionId
                        if (assetId == null) {
                            destination = Destination.ASSETS
                        } else {
                            val sprayViewModel: SprayEntryViewModel = viewModel(
                                key = "spray-$assetId-$linkedSessionId",
                                factory = SprayEntryViewModel.factory(
                                    applicationContext,
                                    assetId,
                                    linkedSessionId
                                )
                            )
                            // Logging a spray from a recording returns to that recording,
                            // so the operator can see the link they just made.
                            val onDone = {
                                destination = if (linkedSessionId != null) {
                                    Destination.RECORDING_DETAIL
                                } else {
                                    Destination.ASSET_DETAIL
                                }
                            }
                            SprayEntryScreen(
                                viewModel = sprayViewModel,
                                onBack = onDone,
                                onSaved = onDone
                            )
                        }
                    }

                    Destination.RECORD -> {
                        val recordingViewModel: RecordingViewModel = viewModel(
                            factory = RecordingViewModel.factory(applicationContext)
                        )
                        RecordScreen(
                            viewModel = recordingViewModel,
                            onOpenTab = { destination = destinationOf(it) }
                        )
                    }

                    Destination.DRAW -> {
                        val drawViewModel: DrawAssetViewModel = viewModel(
                            key = "draw-$drawVisit",
                            factory = DrawAssetViewModel.factory(applicationContext)
                        )
                        DrawAssetScreen(
                            viewModel = drawViewModel,
                            onBack = { destination = Destination.ASSETS }
                        )
                    }

                    Destination.RECORDINGS -> {
                        val recordingsViewModel: RecordingsViewModel = viewModel(
                            factory = RecordingsViewModel.factory(applicationContext)
                        )
                        RecordingsScreen(
                            viewModel = recordingsViewModel,
                            onOpenTab = { destination = destinationOf(it) },
                            onOpenRecording = { sessionId ->
                                selectedSessionId = sessionId
                                destination = Destination.RECORDING_DETAIL
                            }
                        )
                    }

                    Destination.RECORDING_DETAIL -> {
                        val sessionId = selectedSessionId
                        if (sessionId == null) {
                            destination = Destination.RECORDINGS
                        } else {
                            val detailViewModel: RecordingDetailViewModel = viewModel(
                                key = "recording-$sessionId",
                                factory = RecordingDetailViewModel.factory(applicationContext, sessionId)
                            )
                            RecordingDetailScreen(
                                viewModel = detailViewModel,
                                onBack = { destination = Destination.RECORDINGS },
                                onLogSpray = { assetId ->
                                    selectedAssetId = assetId
                                    // Carried through so the spray that gets saved points
                                    // back at the recording it was logged from.
                                    spraySessionId = selectedSessionId
                                    destination = Destination.SPRAY_ENTRY
                                }
                            )
                        }
                    }

                    Destination.SETTINGS -> {
                        val settingsViewModel: SettingsViewModel = viewModel(
                            factory = SettingsViewModel.factory(applicationContext)
                        )
                        val updateViewModel: UpdateViewModel = viewModel(
                            factory = UpdateViewModel.factory(applicationContext)
                        )
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            updateViewModel = updateViewModel,
                            onBack = { destination = Destination.MAP }
                        )
                    }

                    Destination.OFFLINE -> {
                        val offlineViewModel: OfflineViewModel = viewModel(
                            factory = OfflineViewModel.factory(applicationContext)
                        )
                        OfflineScreen(
                            viewModel = offlineViewModel,
                            onOpenTab = { destination = destinationOf(it) },
                            onChooseArea = { destination = Destination.OFFLINE_PICKER }
                        )
                    }

                    Destination.OFFLINE_PICKER -> {
                        val pickerViewModel: OfflineAreaPickerViewModel = viewModel(
                            factory = OfflineAreaPickerViewModel.factory(applicationContext)
                        )
                        OfflineAreaPickerScreen(
                            viewModel = pickerViewModel,
                            onBack = { destination = Destination.OFFLINE },
                            onSaved = { destination = Destination.OFFLINE }
                        )
                    }
                }
            }
        }
    }

    /** The app was already open when the reminder was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedDestination.value = destinationFrom(intent)
    }

    companion object {
        /** The screen an intent would like opened, e.g. from a due reminder. */
        const val EXTRA_DESTINATION = "nz.mckenzie.sprayday.extra.DESTINATION"
        const val DESTINATION_ASSETS = "tracks"
        const val DESTINATION_SETTINGS = "settings"

        private fun destinationFrom(intent: Intent?): Destination? =
            when (intent?.getStringExtra(EXTRA_DESTINATION)) {
                DESTINATION_ASSETS -> Destination.ASSETS
                DESTINATION_SETTINGS -> Destination.SETTINGS
                else -> null
            }

        /** A tab is named after the destination it opens, so a tap maps across by name. */
        private fun destinationOf(tab: Tab): Destination = Destination.valueOf(tab.name)
    }
}



