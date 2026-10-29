package nl.beandesk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Coffee = Color(0xFF6F4E37)
private val Latte = Color(0xFFD7B59A)

private val LightColors = lightColorScheme(primary = Coffee, secondary = Coffee)
private val DarkColors = darkColorScheme(primary = Latte, secondary = Latte)

@Composable
fun BeanDeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
