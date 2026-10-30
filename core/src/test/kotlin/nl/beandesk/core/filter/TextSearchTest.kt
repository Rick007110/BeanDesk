package nl.beandesk.core.filter

import nl.beandesk.core.model.Category
import nl.beandesk.core.model.LatLng
import nl.beandesk.core.model.OsmFacts
import nl.beandesk.core.model.Place
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextSearchTest {
    private val place = Place(1, Category.CAFE, "Café de Zaak", "Oudegracht 12", LatLng(52.09, 5.12), null, OsmFacts(), null)

    @Test
    fun `an empty query matches everything`() {
        assertTrue(place.matchesText(""))
        assertTrue(place.matchesText("   "))
    }

    @Test
    fun `ignores case and accents`() {
        assertTrue(place.matchesText("cafe"))
        assertTrue(place.matchesText("ZAAK"))
        assertTrue(place.matchesText("café"))
    }

    @Test
    fun `every word has to match the name or the address`() {
        assertTrue(place.matchesText("zaak oudegracht"))
        assertTrue(place.matchesText("oudegr 12"))
        assertFalse(place.matchesText("zaak neude"))
    }
}
