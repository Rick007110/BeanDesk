package nl.beandesk.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    @Test
    fun `a report without facts is empty`() {
        assertTrue(Report(placeId = 1).isEmpty)
        assertFalse(Report(placeId = 1, quiet = false).isEmpty)
        assertFalse(Report(placeId = 1, permanentlyClosed = true).isEmpty)
    }

    @Test
    fun `stars alone are a report without facts`() {
        val report = Report(placeId = 1, stars = 4)
        assertFalse(report.isEmpty)
        assertFalse(report.hasFacts)
    }

    @Test
    fun `stars count as a new rating until the next refresh`() {
        assertEquals(Rating(4.0, 1), null.withStars(4))
        assertEquals(Rating(4.0, 3), Rating(4.5, 2).withStars(3))
    }

    @Test
    fun `the first report creates community facts`() {
        val facts = null.withReport(Report(placeId = 1, wifi = WifiStatus.FAST))
        assertEquals(CommunityFacts(wifi = WifiStatus.FAST), facts)
    }

    @Test
    fun `later reports win per fact and keep the rest`() {
        val before = CommunityFacts(wifi = WifiStatus.SLOW, power = PowerLevel.MANY, laptopPolicy = LaptopPolicy.ALLOWED)
        val after = before.withReport(Report(placeId = 1, wifi = WifiStatus.FAST, quiet = true))
        assertEquals(
            CommunityFacts(wifi = WifiStatus.FAST, power = PowerLevel.MANY, quiet = true, laptopPolicy = LaptopPolicy.ALLOWED),
            after,
        )
    }

    @Test
    fun `closure reports add up`() {
        val facts = CommunityFacts(closedReports = 2).withReport(Report(placeId = 1, permanentlyClosed = true))
        assertEquals(3, facts.closedReports)
    }
}
