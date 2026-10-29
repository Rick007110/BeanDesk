package nl.beandesk.core.hours

enum class Weekday { MO, TU, WE, TH, FR, SA, SU }

/** A moment in the week, in the place's local time. */
data class WeekTime(val day: Weekday, val minuteOfDay: Int) {
    init {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "minuteOfDay out of range: $minuteOfDay" }
    }

    internal val minuteOfWeek: Int get() = day.ordinal * MINUTES_PER_DAY + minuteOfDay
}

sealed interface OpenStatus {
    /** [minutesUntilClose] is null when the place never closes (24/7). */
    data class Open(val minutesUntilClose: Int?) : OpenStatus

    /** [minutesUntilOpen] is null when the place has no opening hours at all. */
    data class Closed(val minutesUntilOpen: Int?) : OpenStatus

    /** The opening_hours value is missing or uses syntax we do not evaluate. */
    data object Unknown : OpenStatus
}

/**
 * Evaluates the common subset of the OSM `opening_hours` syntax:
 * weekday ranges and lists, time spans (also past midnight), `off`/`closed` and `24/7`.
 * Holiday rules (PH, SH) are ignored. Anything else (months, weeks, comments,
 * sunrise, fallback rules) makes [parse] return null, which the app shows as "onbekend".
 */
class OpeningHours private constructor(
    // Merged, sorted, non-overlapping spans in minutes since Monday 00:00.
    private val spans: List<Span>,
) {
    fun status(at: WeekTime): OpenStatus {
        if (spans.isEmpty()) return OpenStatus.Closed(null)
        val first = spans.first()
        val last = spans.last()
        if (spans.size == 1 && first.start == 0 && first.end == MINUTES_PER_WEEK) {
            return OpenStatus.Open(null)
        }

        val t = at.minuteOfWeek
        val current = spans.firstOrNull { t >= it.start && t < it.end }
        if (current != null) {
            var minutes = current.end - t
            // Open until Sunday midnight and again from Monday 00:00: one continuous stretch.
            if (current == last && current.end == MINUTES_PER_WEEK && first.start == 0) {
                minutes += first.end
            }
            return OpenStatus.Open(minutes)
        }

        val next = spans.firstOrNull { it.start > t }
        val minutes = if (next != null) next.start - t else first.start + MINUTES_PER_WEEK - t
        return OpenStatus.Closed(minutes)
    }

    /** Half-open interval [start, end) in minutes. */
    private data class Span(val start: Int, val end: Int)

    companion object {
        private val DAY_TOKENS = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        private val HOLIDAY_TOKENS = setOf("PH", "SH")
        private val DAY_PART = Regex("^(Mo|Tu|We|Th|Fr|Sa|Su|PH|SH)(-(Mo|Tu|We|Th|Fr|Sa|Su))?$")
        private val TIME_SPAN = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$")
        private val WHITESPACE = Regex("\\s+")

        fun parse(raw: String?): OpeningHours? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text == "24/7") return OpeningHours(listOf(Span(0, MINUTES_PER_WEEK)))
            if ("||" in text || '"' in text) return null

            // Per weekday the spans in minutes since that day's midnight (the end may pass 24:00).
            // A day no rule mentions stays null, which OSM treats as closed.
            val days = arrayOfNulls<List<Span>>(Weekday.entries.size)
            var anyRule = false
            for (rule in text.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
                val applied = applyRule(rule, days) ?: return null
                anyRule = anyRule || applied
            }
            if (!anyRule) return null

            val weekSpans = buildList {
                days.forEachIndexed { index, daySpans ->
                    daySpans?.forEach { span ->
                        val start = index * MINUTES_PER_DAY + span.start
                        val end = index * MINUTES_PER_DAY + span.end
                        if (end > MINUTES_PER_WEEK) {
                            add(Span(start, MINUTES_PER_WEEK))
                            add(Span(0, end - MINUTES_PER_WEEK))
                        } else {
                            add(Span(start, end))
                        }
                    }
                }
            }
            return OpeningHours(merge(weekSpans))
        }

        /**
         * Applies one `;`-separated rule; a later rule replaces earlier ones for the days it names.
         * Returns true when it set any weekday, false when it only concerned holidays,
         * and null when the syntax is unsupported.
         */
        private fun applyRule(rule: String, days: Array<List<Span>?>): Boolean? {
            val tokens = rule.split(WHITESPACE)
            val selector = parseDays(tokens.first())
            val selected: Set<Int>
            val rest: List<String>
            if (selector != null) {
                if (selector.isEmpty()) return false // Holiday-only rule such as "PH off".
                selected = selector
                rest = tokens.drop(1)
            } else {
                selected = Weekday.entries.indices.toSet()
                rest = tokens
            }

            val spans = parseTimes(rest) ?: return null
            selected.forEach { days[it] = spans }
            return true
        }

        /** Weekday indices, an empty set for holiday-only selectors, or null if [token] is no selector. */
        private fun parseDays(token: String): Set<Int>? {
            val parts = token.split(',')
            if (parts.any { !DAY_PART.matches(it) }) return null
            val result = mutableSetOf<Int>()
            for (part in parts) {
                if (part in HOLIDAY_TOKENS) continue
                val bounds = part.split('-')
                val from = DAY_TOKENS.indexOf(bounds[0])
                val to = DAY_TOKENS.indexOf(bounds.getOrElse(1) { bounds[0] })
                var day = from
                while (true) {
                    result += day
                    if (day == to) break
                    day = (day + 1) % DAY_TOKENS.size // Ranges such as Sa-Mo wrap around.
                }
            }
            return result
        }

        private fun parseTimes(tokens: List<String>): List<Span>? {
            if (tokens.isEmpty()) return listOf(Span(0, MINUTES_PER_DAY)) // "Mo-Fr" alone means all day.
            val joined = tokens.joinToString("")
            if (joined.equals("off", ignoreCase = true) || joined.equals("closed", ignoreCase = true)) {
                return emptyList()
            }
            return joined.split(',').map { parseSpan(it) ?: return null }
        }

        private fun parseSpan(text: String): Span? {
            val match = TIME_SPAN.matchEntire(text) ?: return null
            val (h1, m1, h2, m2) = match.destructured.toList().map { it.toInt() }
            if (h1 > 23 || m1 > 59 || h2 > 48 || m2 > 59) return null
            val start = h1 * 60 + m1
            var end = h2 * 60 + m2
            if (end == start) return null
            if (end < start) end += MINUTES_PER_DAY // 20:00-02:00 runs into the next day.
            if (end > 2 * MINUTES_PER_DAY) return null
            return Span(start, end)
        }

        private fun merge(spans: List<Span>): List<Span> {
            val merged = mutableListOf<Span>()
            for (span in spans.sortedBy { it.start }) {
                val previous = merged.lastOrNull()
                if (previous != null && span.start <= previous.end) {
                    merged[merged.lastIndex] = Span(previous.start, maxOf(previous.end, span.end))
                } else {
                    merged += span
                }
            }
            return merged
        }
    }
}

internal const val MINUTES_PER_DAY = 24 * 60
internal const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY
