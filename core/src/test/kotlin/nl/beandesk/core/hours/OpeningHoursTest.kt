package nl.beandesk.core.hours

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpeningHoursTest {
    private fun status(hours: String, day: Weekday, time: String): OpenStatus {
        val (h, m) = time.split(':').map { it.toInt() }
        val parsed = OpeningHours.parse(hours) ?: error("Could not parse '$hours'")
        return parsed.status(WeekTime(day, h * 60 + m))
    }

    @Test
    fun `weekday range with closing time`() {
        assertEquals(OpenStatus.Open(8 * 60), status("Mo-Fr 08:00-18:00", Weekday.MO, "10:00"))
        assertEquals(OpenStatus.Closed(60), status("Mo-Fr 08:00-18:00", Weekday.MO, "07:00"))
    }

    @Test
    fun `closed over the weekend opens again on Monday`() {
        // Friday 19:00 until Monday 08:00 is 2 days and 13 hours.
        assertEquals(OpenStatus.Closed(61 * 60), status("Mo-Fr 08:00-18:00", Weekday.FR, "19:00"))
    }

    @Test
    fun `span past midnight continues into the next day`() {
        assertEquals(OpenStatus.Open(60), status("Mo-Su 20:00-02:00", Weekday.TU, "01:00"))
        // Sunday night spills into Monday morning across the week boundary.
        assertEquals(OpenStatus.Open(60), status("Mo-Su 20:00-02:00", Weekday.MO, "01:00"))
    }

    @Test
    fun `later rule replaces earlier rule for its days`() {
        val hours = "Mo-Sa 09:00-17:00; Sa 10:00-16:00"
        assertEquals(OpenStatus.Closed(30), status(hours, Weekday.SA, "09:30"))
        assertEquals(OpenStatus.Open(30), status(hours, Weekday.FR, "16:30"))
    }

    @Test
    fun `several spans on one day, with or without a space`() {
        assertEquals(OpenStatus.Closed(30), status("Mo-Fr 08:00-12:00,13:00-17:00", Weekday.MO, "12:30"))
        assertEquals(OpenStatus.Closed(30), status("Mo-Fr 08:00-12:00, 13:00-17:00", Weekday.MO, "12:30"))
    }

    @Test
    fun `day lists and wrapping ranges`() {
        assertEquals(OpenStatus.Open(60), status("Mo,We 09:00-10:00", Weekday.WE, "09:00"))
        assertEquals(OpenStatus.Closed(23 * 60), status("Mo,We 09:00-10:00", Weekday.TU, "10:00"))
        assertEquals(OpenStatus.Open(120), status("Sa-Mo 10:00-12:00", Weekday.SU, "10:00"))
    }

    @Test
    fun `holiday rules are ignored`() {
        assertEquals(OpenStatus.Open(8 * 60), status("Mo-Fr 08:00-18:00; PH off", Weekday.MO, "10:00"))
        assertEquals(OpenStatus.Open(60), status("Mo-Fr 08:00-18:00; PH,Sa 10:00-16:00", Weekday.SA, "15:00"))
    }

    @Test
    fun `always open`() {
        assertEquals(OpenStatus.Open(null), status("24/7", Weekday.TH, "03:00"))
        assertEquals(OpenStatus.Open(null), status("Mo-Su 00:00-24:00", Weekday.TH, "03:00"))
    }

    @Test
    fun `adjacent days join into one stretch`() {
        // Friday 10:00 until Sunday midnight; Monday only opens at 08:00.
        val hours = "Mo-Fr 08:00-24:00; Sa,Su 00:00-24:00"
        assertEquals(OpenStatus.Open(14 * 60 + 2 * 24 * 60), status(hours, Weekday.FR, "10:00"))
    }

    @Test
    fun `closed everywhere`() {
        assertEquals(OpenStatus.Closed(null), status("off", Weekday.MO, "12:00"))
    }

    @Test
    fun `unsupported syntax gives null`() {
        assertNull(OpeningHours.parse(null))
        assertNull(OpeningHours.parse(""))
        assertNull(OpeningHours.parse("Jan-Mar Mo-Fr 08:00-18:00"))
        assertNull(OpeningHours.parse("Mo-Fr 08:00-12:00, We 14:00-18:00"))
        assertNull(OpeningHours.parse("Mo-Fr 08:00-18:00 || \"op afspraak\""))
        assertNull(OpeningHours.parse("sunrise-sunset"))
        assertNull(OpeningHours.parse("Mo-Fr 18:00+"))
        assertNull(OpeningHours.parse("PH off"))
    }
}
