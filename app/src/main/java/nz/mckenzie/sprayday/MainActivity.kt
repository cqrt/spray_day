package nz.mckenzie.sprayday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.mckenzie.sprayday.ui.screens.MapScreen
import nz.mckenzie.sprayday.ui.theme.SprayDayTheme
import nz.mckenzie.sprayday.viewmodel.MapViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SprayDayTheme {
                val mapViewModel: MapViewModel = viewModel(
                    factory = MapViewModel.factory(applicationContext)
                )
                MapScreen(viewModel = mapViewModel)
            }
        }
    }
}

