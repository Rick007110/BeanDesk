package nl.beandesk.core.export

import nl.beandesk.core.model.Category
import nl.beandesk.core.model.LaptopPolicy
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.PowerLevel
import nl.beandesk.core.model.WifiStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CityExportParserTest {
    private val sample = checkNotNull(javaClass.getResource("/city-export-sample.json")).readText()

    @Test
    fun `parses the sample export`() {
        val export = CityExportParser.parse(sample)

        assertEquals("utrecht", export.city)
        assertEquals("Europe/Amsterdam", export.timezone)
        assertEquals(LatLng(52.0907, 5.1214), export.center)
        // The place with an unknown category is skipped.
        assertEquals(listOf(1L, 2L), export.places.map { it.place.id })

        val cafe = export.places[0].place
        assertEquals(Category.CAFE, cafe.category)
        assertEquals(true, cafe.osm.wifiFree)
        assertEquals(WifiStatus.FAST, cafe.community?.wifi)
        assertEquals(PowerLevel.MANY, cafe.community?.power)
        assertEquals(LaptopPolicy.NOT_ON_WEEKENDS, cafe.community?.laptopPolicy)

        val library = export.places[1].place
        assertEquals(Category.LIBRARY, library.category)
        assertNull(library.community)
        assertNull(library.hasWifi)
    }

    @Test
    fun `rejects a newer export version`() {
        val newer = sample.replaceFirst("\"version\": 1", "\"version\": 2")
        assertFailsWith<UnsupportedExportException> { CityExportParser.parse(newer) }
    }
}
