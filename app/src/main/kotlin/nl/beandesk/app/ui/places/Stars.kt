package nl.beandesk.app.ui.places

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.beandesk.app.R
import nl.beandesk.core.model.Rating
import java.util.Locale

/** The amber of rating stars; readable on both the light and the dark theme. */
val StarColor = Color(0xFFF2A900)

/** "4,3" with a Dutch decimal comma. */
fun Rating.averageText(): String = String.format(Locale.forLanguageTag("nl-NL"), "%.1f", average)

/** Five stars, filled to the nearest half, plus "4,3 (12)"; "Nog geen sterren" without ratings. */
@Composable
fun RatingStars(rating: Rating?, modifier: Modifier = Modifier, starSize: Dp = 16.dp) {
    val description = rating?.let { stringResource(R.string.rating_description, it.averageText(), it.count) }
        ?: stringResource(R.string.rating_none)
    // A new rating, after a report or a refresh, fades in over the old one.
    Crossfade(rating, modifier = modifier.clearAndSetSemantics { contentDescription = description }, label = "rating") { shown ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val halves = shown?.let { Math.round(it.average * 2).toInt() } ?: 0
            for (star in 1..5) {
                val icon = when {
                    halves >= star * 2 -> R.drawable.ic_star
                    halves == star * 2 - 1 -> R.drawable.ic_star_half
                    else -> R.drawable.ic_star_outline
                }
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (shown != null) StarColor else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(starSize),
                )
            }
            Text(
                text = shown?.let { stringResource(R.string.rating_summary, it.averageText(), it.count) }
                    ?: stringResource(R.string.rating_none),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** Tap a star to give 1 to 5; tapping the chosen number again clears it. */
@Composable
fun StarPicker(selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..5) {
            val filled = selected != null && star <= selected
            val tint by animateColorAsState(if (filled) StarColor else MaterialTheme.colorScheme.outline, label = "star")
            // A star that fills pops: it starts a bit small and springs to full size, one after
            // the other from the left.
            val scale = remember { Animatable(1f) }
            LaunchedEffect(filled) {
                if (filled && selected != null) {
                    delay(star * 30L)
                    scale.snapTo(0.6f)
                    scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                }
            }
            IconButton(
                onClick = { onSelect(if (selected == star) null else star) },
                modifier = Modifier.semantics { this.selected = star == selected },
            ) {
                Icon(
                    painter = painterResource(if (filled) R.drawable.ic_star else R.drawable.ic_star_outline),
                    contentDescription = stringResource(R.string.report_star, star),
                    tint = tint,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer {
                            scaleX = scale.value
                            scaleY = scale.value
                        },
                )
            }
        }
    }
}
