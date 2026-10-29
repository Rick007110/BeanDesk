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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.beandesk.app.R
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.Report
import nl.beandesk.core.model.WifiStatus

/**
 * "Klopt dit nog?": a few taps on what the user sees now. Starts filled in with what is known,
 * so sending it unchanged confirms it. Takes well under ten seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    place: Place,
    isSending: Boolean,
    sendsReports: Boolean,
    onSend: (Report) -> Unit,
    onDismiss: () -> Unit,
) {
    val known = place.community
    var wifi by rememberSaveable(place.id) {
        mutableStateOf(
            known?.wifi ?: when (place.osm.wifi) {
                true -> WifiStatus.YES
                false -> WifiStatus.NONE
                null -> null
            },
        )
    }
    var power by rememberSaveable(place.id) { mutableStateOf(known?.power) }
    var quiet by rememberSaveable(place.id) { mutableStateOf(known?.quiet) }
    var laptop by rememberSaveable(place.id) { mutableStateOf(known?.laptopPolicy) }
    var closed by rememberSaveable(place.id) { mutableStateOf(false) }
    val report = Report(place.id, wifi, power, quiet, laptop, closed)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.report_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ChoiceRow(
                label = R.string.fact_label_wifi,
                options = listOf(
                    WifiStatus.NONE to R.string.fact_wifi_none,
                    WifiStatus.SLOW to R.string.fact_wifi_slow,
                    WifiStatus.YES to R.string.fact_yes,
                    WifiStatus.FAST to R.string.fact_wifi_fast,
                ),
                selected = wifi,
                onSelect = { wifi = it },
            )
            ChoiceRow(
                label = R.string.fact_label_power,
                options = listOf(
                    PowerLevel.NONE to R.string.fact_power_none,
                    PowerLevel.FEW to R.string.fact_power_few,
                    PowerLevel.MANY to R.string.fact_power_many,
                ),
                selected = power,
                onSelect = { power = it },
            )
            ChoiceRow(
                label = R.string.fact_label_quiet,
                options = listOf(true to R.string.report_quiet, false to R.string.report_busy),
                selected = quiet,
                onSelect = { quiet = it },
            )
            ChoiceRow(
                label = R.string.fact_label_laptop,
                options = listOf(
                    LaptopPolicy.ALLOWED to R.string.fact_laptop_ok,
                    LaptopPolicy.NOT_ON_WEEKENDS to R.string.fact_laptop_weekdays,
                    LaptopPolicy.NOT_ALLOWED to R.string.fact_laptop_no,
                ),
                selected = laptop,
                onSelect = { laptop = it },
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = closed, role = Role.Checkbox, onValueChange = { closed = it }),
            ) {
                Checkbox(checked = closed, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.report_closed), style = MaterialTheme.typography.bodyLarge)
            }

            Button(
                onClick = { onSend(report) },
                enabled = !report.isEmpty && !isSending,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.report_send))
                }
            }
            Text(
                text = stringResource(if (sendsReports) R.string.report_anonymous else R.string.report_sample_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One fact with single-choice chips; tapping the chosen one again clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: Int, options: List<Pair<T, Int>>, selected: T?, onSelect: (T?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(if (value == selected) null else value) },
                    label = { Text(stringResource(text)) },
                )
            }
        }
    }
}
