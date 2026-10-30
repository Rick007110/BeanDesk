package nl.beandesk.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val halves = rating?.let { Math.round(it.average * 2).toInt() } ?: 0
        for (star in 1..5) {
            val icon = when {
                halves >= star * 2 -> R.drawable.ic_star
                halves == star * 2 - 1 -> R.drawable.ic_star_half
                else -> R.drawable.ic_star_outline
            }
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (rating != null) StarColor else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(starSize),
            )
        }
        Text(
            text = rating?.let { stringResource(R.string.rating_summary, it.averageText(), it.count) }
                ?: stringResource(R.string.rating_none),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Tap a star to give 1 to 5; tapping the chosen number again clears it. */
@Composable
fun StarPicker(selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        for (star in 1..5) {
            val filled = selected != null && star <= selected
            IconButton(
                onClick = { onSelect(if (selected == star) null else star) },
                modifier = Modifier.semantics { this.selected = star == selected },
            ) {
                Icon(
                    painter = painterResource(if (filled) R.drawable.ic_star else R.drawable.ic_star_outline),
                    contentDescription = stringResource(R.string.report_star, star),
                    tint = if (filled) StarColor else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}
