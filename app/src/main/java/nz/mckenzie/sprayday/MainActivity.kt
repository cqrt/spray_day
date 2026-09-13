package nz.mckenzie.sprayday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.sprayday.ui.screens.MapScreen
import nz.mckenzie.sprayday.ui.screens.OfflineScreen
import nz.mckenzie.sprayday.ui.theme.SprayDayTheme
import nz.mckenzie.sprayday.viewmodel.MapViewModel
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SprayDayTheme {
                // Two destinations for now; replace with a NavHost once the
                // track library and spray screens land.
                var showOffline by rememberSaveable { mutableStateOf(false) }

                if (showOffline) {
                    val offlineViewModel: OfflineViewModel = viewModel(
                        factory = OfflineViewModel.factory(applicationContext)
                    )
                    OfflineScreen(
                        viewModel = offlineViewModel,
                        onBack = { showOffline = false }
                    )
                } else {
                    val mapViewModel: MapViewModel = viewModel(
                        factory = MapViewModel.factory(applicationContext)
                    )
                    MapScreen(
                        viewModel = mapViewModel,
                        onOpenOffline = { showOffline = true }
                    )
                }
            }
        }
    }
}


