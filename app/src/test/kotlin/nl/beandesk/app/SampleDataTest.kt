package nl.beandesk.app

import nl.beandesk.core.export.CityExportParser
import nl.beandesk.core.model.Category
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Guards the debug sample: it must stay readable by the same parser as the real export. */
class SampleDataTest {
    // Unit tests run with the app module as working directory.
    private val sample = File("src/debug/assets/sample/utrecht.json").readText()

    @Test
    fun `sample export parses completely`() {
        val export = CityExportParser.parse(sample)

        assertEquals("utrecht", export.city)
        assertNotNull(export.center)
        // No place may be dropped for an unknown category or a typo.
        assertEquals(12, export.places.size)
        assertEquals(Category.entries.toSet(), export.places.map { it.place.category }.toSet())
    }

    @Test
    fun `sample shows a place reported as closed`() {
        val export = CityExportParser.parse(sample)
        assertTrue(export.places.any { it.place.isReportedClosed })
    }
}
