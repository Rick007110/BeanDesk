package nl.beandesk.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import nl.beandesk.app.ui.places.PlacesScreen
import nl.beandesk.app.ui.places.PlacesViewModel
import nl.beandesk.app.ui.theme.BeanDeskTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PlacesViewModel by viewModels { PlacesViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeanDeskTheme {
                PlacesScreen(viewModel)
            }
        }
    }
}
