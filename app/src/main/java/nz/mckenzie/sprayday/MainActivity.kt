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
import nz.mckenzie.sprayday.ui.screens.TrackListScreen
import nz.mckenzie.sprayday.ui.theme.SprayDayTheme
import nz.mckenzie.sprayday.viewmodel.DrawTrackViewModel
import nz.mckenzie.sprayday.viewmodel.MapViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel
import nz.mckenzie.sprayday.viewmodel.TrackListViewModel

/** Destinations for now; swap for a NavHost when routes need arguments. */
private enum class Destination { MAP, TRACKS, DRAW, OFFLINE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SprayDayTheme {
                var destination by rememberSaveable { mutableStateOf(Destination.MAP) }

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
                            onDrawTrack = { destination = Destination.DRAW }
                        )
                    }

                    Destination.DRAW -> {
                        val drawViewModel: DrawTrackViewModel = viewModel(
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



