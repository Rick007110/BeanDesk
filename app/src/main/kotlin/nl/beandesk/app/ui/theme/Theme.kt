package nl.beandesk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Coffee = Color(0xFF6F4E37)
private val Latte = Color(0xFFD7B59A)

private val LightColors = lightColorScheme(
    primary = Coffee,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E4D8),
    onPrimaryContainer = Color(0xFF2C1A0E),
    secondary = Coffee,
    secondaryContainer = Color(0xFFF1E9E3),
    onSecondaryContainer = Color(0xFF3A2A1E),
)

private val DarkColors = darkColorScheme(
    primary = Latte,
    onPrimary = Color(0xFF3A2414),
    primaryContainer = Color(0xFF55392A),
    onPrimaryContainer = Color(0xFFF3E4D8),
    secondary = Latte,
    secondaryContainer = Color(0xFF3F3530),
    onSecondaryContainer = Color(0xFFEDE0D8),
)

/** Colours for opening status, which Material's scheme has no roles for. */
@Immutable
data class StatusColors(val open: Color, val closingSoon: Color, val closed: Color, val unknown: Color)

private val LightStatus = StatusColors(
    open = Color(0xFF1E8E3E),
    closingSoon = Color(0xFFB06000),
    closed = Color(0xFFC5221F),
    unknown = Color(0xFF6F6A66),
)

private val DarkStatus = StatusColors(
    open = Color(0xFF81C995),
    closingSoon = Color(0xFFFDD663),
    closed = Color(0xFFF28B82),
    unknown = Color(0xFFB0AAA6),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun BeanDeskTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val dark = darkTheme
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}
