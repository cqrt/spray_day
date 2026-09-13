package nz.mckenzie.sprayday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.sprayday.ui.screens.DrawTrackScreen
import nz.mckenzie.sprayday.ui.screens.MapScreen
import nz.mckenzie.sprayday.ui.screens.OfflineScreen
import nz.mckenzie.sprayday.ui.screens.RecordScreen
import nz.mckenzie.sprayday.ui.screens.SprayEntryScreen
import nz.mckenzie.sprayday.ui.screens.TrackDetailScreen
import nz.mckenzie.sprayday.ui.screens.TrackListScreen
import nz.mckenzie.sprayday.ui.theme.SprayDayTheme
import nz.mckenzie.sprayday.viewmodel.DrawTrackViewModel
import nz.mckenzie.sprayday.viewmodel.MapViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel
import nz.mckenzie.sprayday.viewmodel.RecordingViewModel
import nz.mckenzie.sprayday.viewmodel.SprayEntryViewModel
import nz.mckenzie.sprayday.viewmodel.TrackDetailViewModel
import nz.mckenzie.sprayday.viewmodel.TrackListViewModel

/** Destinations for now; swap for a NavHost when routes need arguments. */
private enum class Destination { MAP, TRACKS, DRAW, RECORD, OFFLINE, TRACK_DETAIL, SPRAY_ENTRY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SprayDayTheme {
                var destination by rememberSaveable { mutableStateOf(Destination.MAP) }
                var selectedTrackId by rememberSaveable { mutableStateOf<Long?>(null) }

                // Bumped on every visit to the draw screen so it gets its own view
                // model: a shared one would carry the previous visit's draft (and
                // its "just saved" signal) into the next drawing session.
                var drawVisit by rememberSaveable { mutableStateOf(0) }

                // System back always returns to the map rather than leaving the app.
                BackHandler(enabled = destination != Destination.MAP) {
                    destination = Destination.MAP
                }

                when (destination) {
                    Destination.MAP -> {
                        val mapViewModel: MapViewModel = viewModel(
                            factory = MapViewModel.factory(applicationContext)
                        )
                        MapScreen(
                            viewModel = mapViewModel,
                            onOpenTracks = { destination = Destination.TRACKS },
                            onOpenOffline = { destination = Destination.OFFLINE }
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
                            onRecordTrack = { destination = Destination.RECORD }
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
                                onRecordSpray = { destination = Destination.SPRAY_ENTRY }
                            )
                        }
                    }

                    Destination.SPRAY_ENTRY -> {
                        val trackId = selectedTrackId
                        if (trackId == null) {
                            destination = Destination.TRACKS
                        } else {
                            val sprayViewModel: SprayEntryViewModel = viewModel(
                                key = "spray-$trackId",
                                factory = SprayEntryViewModel.factory(applicationContext, trackId)
                            )
                            SprayEntryScreen(
                                viewModel = sprayViewModel,
                                onBack = { destination = Destination.TRACK_DETAIL },
                                onSaved = { destination = Destination.TRACK_DETAIL }
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

                    Destination.OFFLINE -> {
                        val offlineViewModel: OfflineViewModel = viewModel(
                            factory = OfflineViewModel.factory(applicationContext)
                        )
                        OfflineScreen(
                            viewModel = offlineViewModel,
                            onBack = { destination = Destination.MAP }
                        )
                    }
                }
            }
        }
    }
}



