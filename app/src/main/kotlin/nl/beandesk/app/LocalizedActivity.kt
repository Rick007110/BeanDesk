package nl.beandesk.app

import android.content.Context
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.beandesk.app.settings.AppSettings
import nl.beandesk.app.settings.ThemeChoice
import nl.beandesk.app.ui.theme.BeanDeskTheme

/**
 * An activity in the language and look chosen in the profile. From Android 13 the system applies
 * the language; before that this does, and an activity created in another language starts over
 * when the user comes back to it.
 */
abstract class LocalizedActivity : ComponentActivity() {
    private var createdIn: String? = null

    protected val settings: AppSettings
        get() = (application as BeanDeskApplication).container.settings

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        createdIn = AppSettings.legacyLanguageTag(newBase)
        createdIn?.let { applyOverrideConfiguration(AppSettings.configurationFor(it)) }
    }

    override fun onRestart() {
        super.onRestart()
        if (AppSettings.legacyLanguageTag(this) != createdIn) recreate()
    }

    /** The app's theme, light or dark as chosen, with the system bars to match. */
    @Composable
    protected fun Themed(content: @Composable () -> Unit) {
        val choice by settings.theme.collectAsStateWithLifecycle()
        val dark = when (choice) {
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
        }
        LaunchedEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
            )
        }
        BeanDeskTheme(darkTheme = dark, content = content)
    }

    private companion object {
        // The same as androidx.activity's defaults for buttons navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
