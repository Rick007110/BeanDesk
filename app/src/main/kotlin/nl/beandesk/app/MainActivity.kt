package nl.beandesk.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import nl.beandesk.app.ui.places.PlacesScreen
import nl.beandesk.app.ui.places.PlacesViewModel
import nl.beandesk.app.ui.profile.ProfileActivity

class MainActivity : LocalizedActivity() {
    private val viewModel: PlacesViewModel by viewModels { PlacesViewModel.Factory }
    private val appIconManager by lazy { (application as BeanDeskApplication).container.appIconManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Themed {
                PlacesScreen(
                    viewModel = viewModel,
                    appIconManager = appIconManager,
                    onOpenProfile = { startActivity(Intent(this, ProfileActivity::class.java)) },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Switching the launcher icon can close the app, so it waits until the app is out of sight.
        if (!isChangingConfigurations) appIconManager.applyPending()
    }
}
