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
import nz.mckenzie.sprayday.ui.screens.DrawTrackScreen
import nz.mckenzie.sprayday.ui.screens.MapScreen
import nz.mckenzie.sprayday.ui.screens.OfflineAreaPickerScreen
import nz.mckenzie.sprayday.ui.screens.OfflineScreen
import nz.mckenzie.sprayday.ui.screens.RecordScreen
import nz.mckenzie.sprayday.ui.screens.RecordingDetailScreen
import nz.mckenzie.sprayday.ui.screens.RecordingsScreen
import nz.mckenzie.sprayday.ui.screens.SettingsScreen
import nz.mckenzie.sprayday.ui.screens.SprayEntryScreen
import nz.mckenzie.sprayday.ui.screens.TrackDetailScreen
import nz.mckenzie.sprayday.ui.screens.TrackListScreen
import nz.mckenzie.sprayday.ui.theme.SprayDayTheme
import nz.mckenzie.sprayday.viewmodel.DrawTrackViewModel
import nz.mckenzie.sprayday.viewmodel.MapViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineAreaPickerViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingDetailViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingsViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingViewModel
import nz.mckenzie.sprayday.viewmodel.SettingsViewModel
import nz.mckenzie.sprayday.viewmodel.SprayEntryViewModel
import nz.mckenzie.sprayday.viewmodel.TrackDetailViewModel
import nz.mckenzie.sprayday.viewmodel.TrackListViewModel

/** Destinations for now; swap for a NavHost when routes need arguments. */
private enum class Destination { MAP, TRACKS, DRAW, RECORD, OFFLINE, OFFLINE_PICKER, TRACK_DETAIL, SPRAY_ENTRY, RECORDINGS, RECORDING_DETAIL, SETTINGS }

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
                var selectedTrackId by rememberSaveable { mutableStateOf<Long?>(null) }
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
                            onOpenTracks = { destination = Destination.TRACKS },
                            onOpenOffline = { destination = Destination.OFFLINE },
                            onOpenSettings = { destination = Destination.SETTINGS },
                            onOpenTrack = { trackId ->
                                selectedTrackId = trackId
                                destination = Destination.TRACK_DETAIL
                            }
                        )
                    }

                    Destination.TRACKS -> {
                        val trackViewModel: TrackListViewModel = viewModel(
                            factory = TrackListViewModel.factory(applicationContext)
                        )
                        TrackListScreen(
                            viewModel = trackViewModel,
                            onBack = { destination = Destination.MAP },
                            onOpenTrack = { trackId ->
                                selectedTrackId = trackId
                                destination = Destination.TRACK_DETAIL
                            },
                            onDrawTrack = {
                                drawVisit++
                                destination = Destination.DRAW
                            },
                            onRecordTrack = { destination = Destination.RECORD },
                            onOpenRecordings = { destination = Destination.RECORDINGS },
                            onOpenSettings = { destination = Destination.SETTINGS }
                        )
                    }

                    Destination.TRACK_DETAIL -> {
                        val trackId = selectedTrackId
                        if (trackId == null) {
                            destination = Destination.TRACKS
                        } else {
                            val detailViewModel: TrackDetailViewModel = viewModel(
                                key = "detail-$trackId",
                                factory = TrackDetailViewModel.factory(applicationContext, trackId)
                            )
                            TrackDetailScreen(
                                viewModel = detailViewModel,
                                onBack = { destination = Destination.TRACKS },
                                onRecordSpray = {
                                    spraySessionId = null
                                    destination = Destination.SPRAY_ENTRY
                                },
                                onOpenRecording = { sessionId ->
                                    selectedSessionId = sessionId
                                    destination = Destination.RECORDING_DETAIL
                                }
                            )
                        }
                    }

                    Destination.SPRAY_ENTRY -> {
                        val trackId = selectedTrackId
                        val linkedSessionId = spraySessionId
                        if (trackId == null) {
                            destination = Destination.TRACKS
                        } else {
                            val sprayViewModel: SprayEntryViewModel = viewModel(
                                key = "spray-$trackId-$linkedSessionId",
                                factory = SprayEntryViewModel.factory(
                                    applicationContext,
                                    trackId,
                                    linkedSessionId
                                )
                            )
                            // Logging a spray from a recording returns to that recording,
                            // so the operator can see the link they just made.
                            val onDone = {
                                destination = if (linkedSessionId != null) {
                                    Destination.RECORDING_DETAIL
                                } else {
                                    Destination.TRACK_DETAIL
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
                            onBack = { destination = Destination.TRACKS }
                        )
                    }

                    Destination.DRAW -> {
                        val drawViewModel: DrawTrackViewModel = viewModel(
                            key = "draw-$drawVisit",
                            factory = DrawTrackViewModel.factory(applicationContext)
                        )
                        DrawTrackScreen(
                            viewModel = drawViewModel,
                            onBack = { destination = Destination.TRACKS }
                        )
                    }

                    Destination.RECORDINGS -> {
                        val recordingsViewModel: RecordingsViewModel = viewModel(
                            factory = RecordingsViewModel.factory(applicationContext)
                        )
                        RecordingsScreen(
                            viewModel = recordingsViewModel,
                            onBack = { destination = Destination.TRACKS },
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
                                onLogSpray = { trackId ->
                                    selectedTrackId = trackId
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
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { destination = Destination.MAP }
                        )
                    }

                    Destination.OFFLINE -> {
                        val offlineViewModel: OfflineViewModel = viewModel(
                            factory = OfflineViewModel.factory(applicationContext)
                        )
                        OfflineScreen(
                            viewModel = offlineViewModel,
                            onBack = { destination = Destination.MAP },
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
        const val DESTINATION_TRACKS = "tracks"

        private fun destinationFrom(intent: Intent?): Destination? =
            when (intent?.getStringExtra(EXTRA_DESTINATION)) {
                DESTINATION_TRACKS -> Destination.TRACKS
                else -> null
            }
    }
}



