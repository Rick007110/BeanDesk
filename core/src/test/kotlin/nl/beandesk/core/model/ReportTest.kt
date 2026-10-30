package nl.beandesk.core.model

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    private val now = Instant.parse("2026-10-30T09:15:00Z")

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
        val facts = null.withReport(Report(placeId = 1, wifi = WifiStatus.FAST), now, Daypart.MORNING)
        assertEquals(CommunityFacts(wifi = WifiStatus.FAST, lastReportAt = now), facts)
    }

    @Test
    fun `later reports win per fact and keep the rest`() {
        val before = CommunityFacts(wifi = WifiStatus.SLOW, power = PowerLevel.MANY, laptopPolicy = LaptopPolicy.ALLOWED)
        val after = before.withReport(Report(placeId = 1, wifi = WifiStatus.FAST, quiet = true), now, Daypart.MORNING)
        assertEquals(
            CommunityFacts(
                wifi = WifiStatus.FAST,
                power = PowerLevel.MANY,
                quiet = true,
                laptopPolicy = LaptopPolicy.ALLOWED,
                lastReportAt = now,
                quietByDaypart = mapOf(Daypart.MORNING to QuietCount(quiet = 1, busy = 0)),
            ),
            after,
        )
    }

    @Test
    fun `closure reports add up`() {
        val facts = CommunityFacts(closedReports = 2).withReport(Report(placeId = 1, permanentlyClosed = true), now, Daypart.MORNING)
        assertEquals(3, facts.closedReports)
    }

    @Test
    fun `quiet or busy counts for the part of the day`() {
        val before = CommunityFacts(quiet = true, quietByDaypart = mapOf(Daypart.MORNING to QuietCount(quiet = 2, busy = 0)))
        val busyMorning = before.withReport(Report(placeId = 1, quiet = false), now, Daypart.MORNING)
        assertEquals(false, busyMorning.quiet)
        assertEquals(mapOf(Daypart.MORNING to QuietCount(quiet = 2, busy = 1)), busyMorning.quietByDaypart)

        val quietWeekend = busyMorning.withReport(Report(placeId = 1, quiet = true), now, Daypart.WEEKEND)
        assertEquals(QuietCount(quiet = 1, busy = 0), quietWeekend.quietByDaypart[Daypart.WEEKEND])

        // Other facts leave the parts of the day alone.
        val wifiOnly = quietWeekend.withReport(Report(placeId = 1, wifi = WifiStatus.SLOW), now, Daypart.EVENING)
        assertEquals(quietWeekend.quietByDaypart, wifiOnly.quietByDaypart)
    }

    @Test
    fun `stars alone change no facts`() {
        val before = CommunityFacts(wifi = WifiStatus.SLOW, lastReportAt = now - Duration.ofDays(400))
        assertEquals(before, before.withReport(Report(placeId = 1, stars = 5), now, Daypart.MORNING))
    }

    @Test
    fun `facts nobody confirmed for a year are stale`() {
        assertTrue(CommunityFacts(lastReportAt = now - Duration.ofDays(366)).isStale(now))
        assertFalse(CommunityFacts(lastReportAt = now - Duration.ofDays(300)).isStale(now))
        // Unknown: an export from before the date was sent along.
        assertFalse(CommunityFacts().isStale(now))
    }
}
