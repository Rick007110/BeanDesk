package nl.beandesk.core.filter

import nl.beandesk.core.hours.OpenStatus
import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.hours.Weekday
import nl.beandesk.core.model.Category
import nl.beandesk.core.model.CommunityFacts
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.OsmFacts
import nl.beandesk.core.model.Place
import nl.beandesk.core.model.WifiStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceFilterTest {
    private val tuesday = WeekTime(Weekday.TU, 10 * 60)
    private val saturday = WeekTime(Weekday.SA, 10 * 60)
    private val open = OpenStatus.Open(60)

    private fun place(
        osm: OsmFacts = OsmFacts(),
        community: CommunityFacts? = null,
        category: Category = Category.CAFE,
    ) = Place(1, category, "Test", null, LatLng(52.09, 5.12), null, osm, community)

    @Test
    fun `no filters matches everything`() {
        assertTrue(PlaceFilter().matches(place(), OpenStatus.Unknown, tuesday))
    }

    @Test
    fun `user reports win over OSM tags for wifi`() {
        val filter = PlaceFilter(wifi = true)
        assertTrue(filter.matches(place(osm = OsmFacts(wifi = true)), open, tuesday))
        assertFalse(filter.matches(place(), open, tuesday))
        val reportedNone = place(osm = OsmFacts(wifi = true), community = CommunityFacts(wifi = WifiStatus.NONE))
        assertFalse(filter.matches(reportedNone, open, tuesday))
        val reportedSlow = place(osm = OsmFacts(wifi = false), community = CommunityFacts(wifi = WifiStatus.SLOW))
        assertTrue(filter.matches(reportedSlow, open, tuesday))
    }

    @Test
    fun `open now needs a known open status`() {
        val filter = PlaceFilter(openNow = true)
        assertTrue(filter.matches(place(), open, tuesday))
        assertFalse(filter.matches(place(), OpenStatus.Closed(30), tuesday))
        assertFalse(filter.matches(place(), OpenStatus.Unknown, tuesday))
    }

    @Test
    fun `laptop policy depends on the day`() {
        val filter = PlaceFilter(laptopAllowed = true)
        val weekdaysOnly = place(community = CommunityFacts(laptopPolicy = LaptopPolicy.NOT_ON_WEEKENDS))
        assertTrue(filter.matches(weekdaysOnly, open, tuesday))
        assertFalse(filter.matches(weekdaysOnly, open, saturday))
        assertFalse(filter.matches(place(), open, tuesday))
    }

    @Test
    fun `category filter`() {
        val librariesOnly = PlaceFilter(categories = setOf(Category.LIBRARY))
        assertFalse(librariesOnly.matches(place(), open, tuesday))
        assertTrue(librariesOnly.matches(place(category = Category.LIBRARY), open, tuesday))
        assertTrue(PlaceFilter(categories = emptySet()).matches(place(), open, tuesday))
    }
}
