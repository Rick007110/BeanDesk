package nl.beandesk.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.beandesk.app.R
import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.model.Place

@Composable
fun PlaceList(
    items: List<PlaceItem>,
    attribution: String,
    onPlaceClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        if (items.isEmpty()) {
            item { Text(stringResource(R.string.no_places), Modifier.padding(16.dp)) }
        }
        items(items, key = { it.place.id }) { item ->
            ListItem(
                headlineContent = { Text(item.place.name) },
                supportingContent = {
                    Text(listOfNotNull(statusText(item.status), item.place.address).joinToString(" · "))
                },
                modifier = Modifier.clickable { onPlaceClick(item.place.id) },
            )
        }
        item {
            Text(attribution, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun PlaceCard(
    item: PlaceItem,
    onRoute: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.place.name, style = MaterialTheme.typography.titleMedium)
            item.place.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(statusText(item.status), style = MaterialTheme.typography.bodyMedium)
            Text(factsText(item.place), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRoute) { Text(stringResource(R.string.route)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun statusText(status: OpenStatus): String = when (status) {
    is OpenStatus.Open -> status.minutesUntilClose
        ?.let { stringResource(R.string.status_open_closes_in, durationText(it)) }
        ?: stringResource(R.string.status_always_open)
    is OpenStatus.Closed -> status.minutesUntilOpen
        ?.let { stringResource(R.string.status_closed_opens_in, durationText(it)) }
        ?: stringResource(R.string.status_closed)
    OpenStatus.Unknown -> stringResource(R.string.status_unknown)
}

@Composable
private fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(R.string.duration_minutes, rest)
        hours >= 24 -> stringResource(R.string.duration_more_than_a_day)
        rest == 0 -> stringResource(R.string.duration_hours, hours)
        else -> stringResource(R.string.duration_hours_minutes, hours, rest)
    }
}

@Composable
private fun factsText(place: Place): String {
    val wifi = yesNoUnknown(place.hasWifi)
    val power = yesNoUnknown(place.hasPower)
    return stringResource(R.string.place_facts, wifi, power)
}

@Composable
private fun yesNoUnknown(value: Boolean?): String = stringResource(
    when (value) {
        true -> R.string.yes
        false -> R.string.no
        null -> R.string.unknown
    },
)
