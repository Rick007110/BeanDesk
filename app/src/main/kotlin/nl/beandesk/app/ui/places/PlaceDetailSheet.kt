package nl.beandesk.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.beandesk.app.R
import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.model.Daypart
import nl.beandesk.core.route.TravelMode
import java.time.ZonedDateTime

/** Details of one place, sliding up over the list or the map. [now] is the time in the place's area. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDetailSheet(
    item: PlaceItem,
    now: ZonedDateTime?,
    showMapButton: Boolean,
    onDismiss: () -> Unit,
    onRoute: (TravelMode) -> Unit,
    onShowOnMap: () -> Unit,
    onReport: () -> Unit,
) {
    val place = item.place
    val weekTime = now?.let(WeekTime::of)
    BottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(place.category, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = placeSubtitle(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RatingStars(place.rating, starSize = 20.dp)
            StatusLine(item.status)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onRoute(TravelMode.BICYCLING) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    ButtonIcon(R.drawable.ic_bike)
                    Text(stringResource(R.string.route_bike))
                }
                OutlinedButton(onClick = { onRoute(TravelMode.WALKING) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    ButtonIcon(R.drawable.ic_walk)
                    Text(stringResource(R.string.route_walk))
                }
                if (showMapButton) {
                    OutlinedButton(onClick = onShowOnMap) { Text(stringResource(R.string.show_on_map)) }
                }
            }

            HorizontalDivider()

            FactRow(R.drawable.ic_wifi, R.string.fact_label_wifi, wifiFact(place))
            FactRow(R.drawable.ic_power, R.string.fact_label_power, powerFact(place))
            FactRow(R.drawable.ic_laptop, R.string.fact_label_laptop, laptopFact(place))
            FactRow(R.drawable.ic_quiet, R.string.fact_label_quiet, quietFact(place))
            LastConfirmed(place.community, item.isStale)

            place.community?.quietByDaypart?.takeIf { it.isNotEmpty() }?.let { counts ->
                HorizontalDivider()
                DaypartQuietness(counts, current = weekTime?.let(Daypart::of))
            }

            HorizontalDivider()
            WeekHours(place.openingHours, today = weekTime?.day)

            HorizontalDivider()

            // "Klopt dit nog?": the quickest way for users to add or confirm what they see.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.report_prompt_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.report_prompt_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = onReport) { Text(stringResource(R.string.report_open)) }
            }
        }
    }
}

@Composable
private fun ButtonIcon(iconRes: Int) {
    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}

@Composable
private fun FactRow(iconRes: Int, labelRes: Int, fact: FactLine) {
    val source = when (fact.fromUsers) {
        true -> stringResource(R.string.source_users)
        false -> stringResource(R.string.source_osm)
        null -> null
    }
    FactRow(iconRes, labelRes, stringResource(fact.valueRes), source)
}

@Composable
private fun FactRow(iconRes: Int, labelRes: Int, value: String, source: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        // Wider than the label, so raw opening hours wrap instead of squeezing it.
        Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
            )
            if (source != null) {
                Text(
                    text = source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
