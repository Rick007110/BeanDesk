package nl.beandesk.core.filter

import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.hours.Weekday
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.Place

/**
 * The filter chips on the map and list. Every enabled filter must match;
 * an empty [categories] set means all categories.
 */
data class PlaceFilter(
    val wifi: Boolean = false,
    val power: Boolean = false,
    val openNow: Boolean = false,
    val quiet: Boolean = false,
    val laptopAllowed: Boolean = false,
    val categories: Set<Category> = emptySet(),
) {
    fun matches(place: Place, status: OpenStatus, at: WeekTime): Boolean {
        if (categories.isNotEmpty() && place.category !in categories) return false
        if (wifi && place.hasWifi != true) return false
        if (power && place.hasPower != true) return false
        if (openNow && status !is OpenStatus.Open) return false
        if (quiet && place.community?.quiet != true) return false
        if (laptopAllowed && !place.allowsLaptop(at)) return false
        return true
    }
}

fun Place.allowsLaptop(at: WeekTime): Boolean = when (community?.laptopPolicy) {
    LaptopPolicy.ALLOWED -> true
    LaptopPolicy.NOT_ON_WEEKENDS -> at.day != Weekday.SA && at.day != Weekday.SU
    LaptopPolicy.NOT_ALLOWED, null -> false
}
