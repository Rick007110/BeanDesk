package nl.beandesk.core.model

import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.hours.Weekday
import kotlin.test.Test
import kotlin.test.assertEquals

class DaypartTest {
    private fun at(day: Weekday, time: String): Daypart {
        val (h, m) = time.split(':').map { it.toInt() }
        return Daypart.of(WeekTime(day, h * 60 + m))
    }

    @Test
    fun `parts of a weekday`() {
        assertEquals(Daypart.MORNING, at(Weekday.MO, "00:00"))
        assertEquals(Daypart.MORNING, at(Weekday.MO, "11:59"))
        assertEquals(Daypart.AFTERNOON, at(Weekday.TU, "12:00"))
        assertEquals(Daypart.AFTERNOON, at(Weekday.FR, "16:59"))
        assertEquals(Daypart.EVENING, at(Weekday.FR, "17:00"))
    }

    @Test
    fun `the weekend is one part`() {
        assertEquals(Daypart.WEEKEND, at(Weekday.SA, "09:00"))
        assertEquals(Daypart.WEEKEND, at(Weekday.SU, "23:30"))
    }

    @Test
    fun `two out of three decide`() {
        assertEquals(Quietness.QUIET, QuietCount(quiet = 1, busy = 0).verdict)
        assertEquals(Quietness.QUIET, QuietCount(quiet = 2, busy = 1).verdict)
        assertEquals(Quietness.BUSY, QuietCount(quiet = 1, busy = 2).verdict)
        assertEquals(Quietness.MIXED, QuietCount(quiet = 1, busy = 1).verdict)
        assertEquals(Quietness.MIXED, QuietCount(quiet = 3, busy = 2).verdict)
    }
}
