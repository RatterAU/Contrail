package app.contrail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.contrail.ui.map.MapScreen
import app.contrail.ui.map.MapViewModel
import app.contrail.ui.theme.ContrailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ContrailApp).container
        setContent {
            val dark = isSystemInDarkTheme()
            ContrailTheme(dark) {
                val vm: MapViewModel = viewModel(factory = viewModelFactory { initializer { MapViewModel(container) } })
                MapScreen(vm, dark)
            }
        }
    }
}
