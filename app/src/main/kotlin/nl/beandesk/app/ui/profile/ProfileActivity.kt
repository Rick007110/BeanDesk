package nl.beandesk.app.ui.profile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.beandesk.app.BeanDeskApplication
import nl.beandesk.app.BuildConfig
import nl.beandesk.app.LocalizedActivity
import nl.beandesk.app.R

/** Your reports and stars, the app's settings, privacy and deleting your account. */
class ProfileActivity : LocalizedActivity() {
    private val viewModel: ProfileViewModel by viewModels { ProfileViewModel.Factory }
    private val appIconManager by lazy { (application as BeanDeskApplication).container.appIconManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Themed {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val theme by settings.theme.collectAsStateWithLifecycle()
                var appIcon by remember { mutableStateOf(appIconManager.selected()) }
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(viewModel) {
                    viewModel.messages.collect { message ->
                        snackbarHostState.showSnackbar(
                            getString(
                                when (message) {
                                    ProfileMessage.ACCOUNT_DELETED -> R.string.account_deleted
                                    ProfileMessage.DELETE_FAILED -> R.string.account_delete_failed
                                },
                            ),
                        )
                    }
                }
                ProfileContent(
                    state = state,
                    settings = SettingsState(
                        theme = theme,
                        language = settings.language(),
                        appIcon = appIcon,
                    ),
                    versionName = BuildConfig.VERSION_NAME,
                    snackbarHostState = snackbarHostState,
                    onBack = ::finish,
                    onRetry = viewModel::load,
                    onThemeChange = settings::setTheme,
                    onLanguageChange = { settings.setLanguage(it, this) },
                    onAppIconChange = { icon ->
                        appIconManager.select(icon)
                        appIcon = icon
                    },
                    onDeleteAccount = viewModel::deleteAccount,
                )
            }
        }
    }
}
