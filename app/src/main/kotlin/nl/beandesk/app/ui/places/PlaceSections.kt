package nl.beandesk.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nl.beandesk.app.R
import nl.beandesk.app.ui.theme.LocalStatusColors
import nl.beandesk.core.hours.DayHours
import nl.beandesk.core.hours.OpeningHours
import nl.beandesk.core.hours.Weekday
import nl.beandesk.core.model.CommunityFacts
import nl.beandesk.core.model.Daypart
import nl.beandesk.core.model.QuietCount
import nl.beandesk.core.model.Quietness
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

// Parts of the place's sheet: the week's opening hours, how busy it is per part of the day, and
// when users last confirmed what they said.

/** Monday to Sunday with today stressed; the raw OpenStreetMap text when the app cannot read it. */
@Composable
fun WeekHours(openingHours: String?, today: Weekday?) {
    val hours = remember(openingHours) { OpeningHours.parse(openingHours) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(R.drawable.ic_schedule, R.string.fact_label_hours)
        when {
            hours != null -> {
                hours.week.forEach { day -> DayRow(day, isToday = day.day == today) }
                if (hours.mentionsHolidays) Note(stringResource(R.string.hours_holidays))
            }
            openingHours != null -> {
                Text(openingHours, style = MaterialTheme.typography.bodyLarge)
                Note(stringResource(R.string.hours_as_in_osm))
            }
            else -> Text(stringResource(R.string.fact_unknown), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DayRow(day: DayHours, isToday: Boolean) {
    val weight = if (isToday) FontWeight.SemiBold else FontWeight.Normal
    val today = stringResource(R.string.today)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isToday) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Read out as one line: "Maandag, 08:00–18:00, vandaag".
            .semantics(mergeDescendants = true) { if (isToday) stateDescription = today },
    ) {
        Text(dayName(day.day), fontWeight = weight, modifier = Modifier.weight(1f))
        Text(dayHoursText(day), fontWeight = weight, textAlign = TextAlign.End)
    }
}

@Composable
private fun dayName(day: Weekday): String {
    val locale = LocalConfiguration.current.locales[0]
    return DayOfWeek.of(day.ordinal + 1).getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
private fun dayHoursText(day: DayHours): String = when {
    day.isClosed -> stringResource(R.string.hours_closed)
    day.isAllDay -> stringResource(R.string.hours_all_day)
    else -> day.spans.joinToString(", ") { "${clock(it.start)}–${clock(it.end)}" }
}

/** "08:00"; past midnight as the next day's time (26:00 is 02:00), but a day's end as 24:00. */
private fun clock(minutes: Int): String {
    val time = if (minutes > 24 * 60) minutes - 24 * 60 else minutes
    return "%02d:%02d".format(time / 60, time % 60)
}

/** What users said about how busy it is, per part of the day; nothing when nobody said so. */
@Composable
fun DaypartQuietness(counts: Map<Daypart, QuietCount>, current: Daypart?) {
    if (counts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(R.drawable.ic_quiet, R.string.daypart_title)
        Daypart.entries.forEach { part ->
            DaypartRow(part, counts[part], isNow = part == current)
        }
        Note(stringResource(R.string.daypart_note))
    }
}

@Composable
private fun DaypartRow(part: Daypart, count: QuietCount?, isNow: Boolean) {
    val weight = if (isNow) FontWeight.SemiBold else FontWeight.Normal
    val now = stringResource(R.string.daypart_now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isNow) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { if (isNow) stateDescription = now },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(part.labelRes), fontWeight = weight, modifier = Modifier.weight(1f))
        if (count == null) {
            Text(stringResource(R.string.daypart_no_reports), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(count.verdict.labelRes), fontWeight = FontWeight.SemiBold, color = quietnessColor(count.verdict))
            Spacer(Modifier.width(6.dp))
            Text(
                text = pluralStringResource(R.plurals.daypart_reports, count.total, count.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val Daypart.labelRes: Int
    get() = when (this) {
        Daypart.MORNING -> R.string.daypart_morning
        Daypart.AFTERNOON -> R.string.daypart_afternoon
        Daypart.EVENING -> R.string.daypart_evening
        Daypart.WEEKEND -> R.string.daypart_weekend
    }

private val Quietness.labelRes: Int
    get() = when (this) {
        Quietness.QUIET -> R.string.quietness_quiet
        Quietness.MIXED -> R.string.quietness_mixed
        Quietness.BUSY -> R.string.quietness_busy
    }

@Composable
private fun quietnessColor(quietness: Quietness): Color {
    val colors = LocalStatusColors.current
    return when (quietness) {
        Quietness.QUIET -> colors.open
        Quietness.MIXED -> colors.closingSoon
        Quietness.BUSY -> colors.closed
    }
}

/**
 * When users last confirmed what they said about the place; after a year a warning that it may
 * be outdated. Nothing when the date is unknown.
 */
@Composable
fun LastConfirmed(community: CommunityFacts?, isStale: Boolean) {
    val at = community?.lastReportAt ?: return
    val date = dateText(at)
    if (!isStale) {
        Note(stringResource(R.string.last_confirmed, date))
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_history),
            contentDescription = null,
            tint = LocalStatusColors.current.closingSoon,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.stale_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.stale_body, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "28 okt 2026", in the app's language and the phone's time zone. */
@Composable
fun dateText(at: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(at.atZone(ZoneId.systemDefault()))
}

@Composable
private fun SectionTitle(iconRes: Int, labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
