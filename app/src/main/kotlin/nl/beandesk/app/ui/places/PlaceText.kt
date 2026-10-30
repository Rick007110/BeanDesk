package nl.beandesk.app.ui.places

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import nl.beandesk.app.R
import nl.beandesk.app.ui.theme.LocalStatusColors
import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.WifiStatus
import java.util.Locale
import kotlin.math.roundToInt

// Shared wording and colours for places, used by the list, the detail sheet and the map.

/** Closing within this many minutes shows as "sluit binnenkort" in amber. */
private const val CLOSING_SOON_MINUTES = 60

val Category.iconRes: Int
    get() = when (this) {
        Category.CAFE -> R.drawable.ic_cafe
        Category.LIBRARY -> R.drawable.ic_library
        Category.COWORKING -> R.drawable.ic_work
    }

val Category.labelRes: Int
    get() = when (this) {
        Category.CAFE -> R.string.category_cafe_one
        Category.LIBRARY -> R.string.category_library_one
        Category.COWORKING -> R.string.category_coworking_one
    }

val Category.filterLabelRes: Int
    get() = when (this) {
        Category.CAFE -> R.string.category_cafe
        Category.LIBRARY -> R.string.category_library
        Category.COWORKING -> R.string.category_coworking
    }

/** "350 m" or "1,2 km" (in English "1.2 km"). */
fun distanceText(meters: Double, locale: Locale): String {
    val roundedMeters = (meters / 10).roundToInt() * 10
    return if (roundedMeters < 1000) "$roundedMeters m" else String.format(locale, "%.1f km", meters / 1000)
}

/** "Café · 350 m · Oudegracht 120"; parts that are unknown are left out. */
@Composable
fun placeSubtitle(item: PlaceItem): String = listOfNotNull(
    stringResource(item.place.category.labelRes),
    item.distanceMeters?.let { distanceText(it, LocalConfiguration.current.locales[0]) },
    item.place.address,
).joinToString(" · ")

@Composable
fun statusColor(status: OpenStatus): Color {
    val colors = LocalStatusColors.current
    return when (status) {
        is OpenStatus.Open -> {
            val minutes = status.minutesUntilClose
            if (minutes != null && minutes <= CLOSING_SOON_MINUTES) colors.closingSoon else colors.open
        }
        is OpenStatus.Closed -> colors.closed
        OpenStatus.Unknown -> colors.unknown
    }
}

@Composable
fun statusText(status: OpenStatus): String = when (status) {
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

/** A short label on a list card, such as "Snelle wifi". [warning] marks bad news. */
data class FactChip(val iconRes: Int, val labelRes: Int, val warning: Boolean = false)

/** The facts worth showing on a card; unknown facts are left out. */
fun factChips(place: Place): List<FactChip> = buildList {
    val community = place.community
    when {
        community?.wifi == WifiStatus.FAST -> add(FactChip(R.drawable.ic_wifi, R.string.chip_wifi_fast))
        community?.wifi == WifiStatus.SLOW -> add(FactChip(R.drawable.ic_wifi, R.string.chip_wifi_slow))
        place.hasWifi == true -> add(FactChip(R.drawable.ic_wifi, R.string.chip_wifi))
    }
    when {
        community?.power == PowerLevel.MANY -> add(FactChip(R.drawable.ic_power, R.string.chip_power_many))
        place.hasPower == true -> add(FactChip(R.drawable.ic_power, R.string.chip_power))
    }
    when (community?.laptopPolicy) {
        LaptopPolicy.ALLOWED -> add(FactChip(R.drawable.ic_laptop, R.string.chip_laptop_ok))
        LaptopPolicy.NOT_ON_WEEKENDS -> add(FactChip(R.drawable.ic_laptop, R.string.chip_laptop_weekdays))
        LaptopPolicy.NOT_ALLOWED -> add(FactChip(R.drawable.ic_laptop, R.string.chip_laptop_no, warning = true))
        null -> Unit
    }
    if (community?.quiet == true) add(FactChip(R.drawable.ic_quiet, R.string.chip_quiet))
}

/** One line in the detail sheet: what is known, and whether users or OpenStreetMap say so. */
data class FactLine(val valueRes: Int, val fromUsers: Boolean?)

fun wifiFact(place: Place): FactLine {
    val reported = place.community?.wifi
    if (reported != null) {
        val value = when (reported) {
            WifiStatus.NONE -> R.string.fact_wifi_none
            WifiStatus.YES -> R.string.fact_yes
            WifiStatus.SLOW -> R.string.fact_wifi_slow
            WifiStatus.FAST -> R.string.fact_wifi_fast
        }
        return FactLine(value, fromUsers = true)
    }
    return when (place.osm.wifi) {
        true -> FactLine(if (place.osm.wifiFree == true) R.string.fact_wifi_free else R.string.fact_yes, fromUsers = false)
        false -> FactLine(R.string.fact_wifi_none, fromUsers = false)
        null -> FactLine(R.string.fact_unknown, fromUsers = null)
    }
}

fun powerFact(place: Place): FactLine {
    val reported = place.community?.power
    if (reported != null) {
        val value = when (reported) {
            PowerLevel.NONE -> R.string.fact_power_none
            PowerLevel.FEW -> R.string.fact_power_few
            PowerLevel.MANY -> R.string.fact_power_many
        }
        return FactLine(value, fromUsers = true)
    }
    return when (place.osm.power) {
        true -> FactLine(R.string.fact_yes, fromUsers = false)
        false -> FactLine(R.string.fact_power_none, fromUsers = false)
        null -> FactLine(R.string.fact_unknown, fromUsers = null)
    }
}

fun laptopFact(place: Place): FactLine = when (place.community?.laptopPolicy) {
    LaptopPolicy.ALLOWED -> FactLine(R.string.fact_laptop_ok, fromUsers = true)
    LaptopPolicy.NOT_ON_WEEKENDS -> FactLine(R.string.fact_laptop_weekdays, fromUsers = true)
    LaptopPolicy.NOT_ALLOWED -> FactLine(R.string.fact_laptop_no, fromUsers = true)
    null -> FactLine(R.string.fact_unknown, fromUsers = null)
}

fun quietFact(place: Place): FactLine = when (place.community?.quiet) {
    true -> FactLine(R.string.fact_quiet_yes, fromUsers = true)
    false -> FactLine(R.string.fact_quiet_no, fromUsers = true)
    null -> FactLine(R.string.fact_unknown, fromUsers = null)
}
