package nl.beandesk.core.model

import nl.beandesk.core.hours.WeekTime
import nl.beandesk.core.hours.Weekday

/** Parts of the week that users' "rustig" and "druk" are counted in, as the database does. */
enum class Daypart {
    MORNING,
    AFTERNOON,
    EVENING,
    WEEKEND,
    ;

    companion object {
        /** Morning before 12:00, afternoon until 17:00, evening after; Saturday and Sunday are one part. */
        fun of(at: WeekTime): Daypart = when {
            at.day == Weekday.SA || at.day == Weekday.SU -> WEEKEND
            at.minuteOfDay < 12 * 60 -> MORNING
            at.minuteOfDay < 17 * 60 -> AFTERNOON
            else -> EVENING
        }
    }
}

enum class Quietness { QUIET, MIXED, BUSY }

/** How many users said a place was quiet or busy in one part of the day. */
data class QuietCount(val quiet: Int, val busy: Int) {
    val total: Int get() = quiet + busy

    /** Quiet or busy when at least two out of three say so, otherwise mixed. */
    val verdict: Quietness
        get() = when {
            quiet * 3 >= total * 2 -> Quietness.QUIET
            busy * 3 >= total * 2 -> Quietness.BUSY
            else -> Quietness.MIXED
        }

    fun plus(isQuiet: Boolean): QuietCount = if (isQuiet) copy(quiet = quiet + 1) else copy(busy = busy + 1)
}
