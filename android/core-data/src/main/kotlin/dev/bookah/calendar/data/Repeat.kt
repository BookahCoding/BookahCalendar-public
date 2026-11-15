package dev.bookah.calendar.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneOffsetTransitionRule

enum class RepeatFreq { Daily, Weekly, Monthly, Yearly }

sealed interface RepeatEnd {
    data object Never : RepeatEnd

    /** The last day an event may happen on (that day is included). */
    data class Until(val day: LocalDate) : RepeatEnd
    data class Count(val times: Int) : RepeatEnd
}

/**
 * How a NEW event repeats, as the form sets it. [weekdays] only matters for [RepeatFreq.Weekly] (empty = the weekday of the start).
 * Monthly means "on the same day of the month as the start" and yearly "on the same date"; a month without that day is skipped (the standard's rule).
 */
data class RepeatRule(
    val freq: RepeatFreq,
    val interval: Int = 1,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val end: RepeatEnd = RepeatEnd.Never,
) {
    /** Why this rule cannot be saved, or null. [startDay] is the first day of the event. */
    fun problem(startDay: LocalDate): String? = when {
        interval !in 1..99 -> "every must be 1 to 99"
        end is RepeatEnd.Count && end.times !in 1..999 -> "times must be 1 to 999"
        end is RepeatEnd.Until && end.day.isBefore(startDay) -> "ends before it starts"
        else -> null
    }
}

private val DAY_CODES = mapOf(
    DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
    DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
)

object RepeatWriter {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** The days to move the start forward so it falls on a chosen weekday (a weekly rule whose days do not include the start's weekday). */
    fun startShiftDays(rule: RepeatRule, startDay: LocalDate): Long {
        if (rule.freq != RepeatFreq.Weekly || rule.weekdays.isEmpty() || startDay.dayOfWeek in rule.weekdays) return 0
        return (1L..7L).first { startDay.plusDays(it).dayOfWeek in rule.weekdays }
    }

    /**
     * The RRULE value. [allDay]: an UNTIL is a date; otherwise it is the end of that day in [zone], as a UTC time (the standard asks for UTC
     * when the start has a time zone).
     */
    fun rrule(rule: RepeatRule, allDay: Boolean, zone: ZoneId): String {
        val parts = ArrayList<String>()
        parts += "FREQ=" + rule.freq.name.uppercase()
        if (rule.interval != 1) parts += "INTERVAL=${rule.interval}"
        if (rule.freq == RepeatFreq.Weekly && rule.weekdays.isNotEmpty()) {
            parts += "BYDAY=" + DayOfWeek.entries.filter { it in rule.weekdays }.joinToString(",") { DAY_CODES.getValue(it) }
        }
        when (val e = rule.end) {
            RepeatEnd.Never -> {}
            is RepeatEnd.Count -> parts += "COUNT=${e.times}"
            is RepeatEnd.Until -> parts += "UNTIL=" + if (allDay) DATE.format(e.day)
            else IcsEdit.utc(e.day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1000)
        }
        return parts.joinToString(";")
    }

    fun localText(ms: Long, zone: ZoneId): String = LOCAL.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** A VTIMEZONE block (lines, no trailing break) that describes [zone]'s current rules, so any calendar program can place local times. */
    fun vtimezone(zone: ZoneId, nowMs: Long): List<String> {
        val rules = zone.rules
        val out = ArrayList<String>()
        out += "BEGIN:VTIMEZONE"; out += "TZID:${zone.id}"
        fun off(o: ZoneOffset): String {
            val t = o.totalSeconds
            val a = Math.abs(t)
            return (if (t < 0) "-" else "+") + "%02d%02d".format(a / 3600, (a % 3600) / 60)
        }
        val tr = rules.transitionRules
        if (tr.isEmpty()) {
            val o = rules.getOffset(Instant.ofEpochMilli(nowMs))
            out += "BEGIN:STANDARD"; out += "DTSTART:19700101T000000"; out += "TZOFFSETFROM:${off(o)}"; out += "TZOFFSETTO:${off(o)}"; out += "END:STANDARD"
        } else {
            for (r in tr) {
                val t = r.createTransition(1970)
                val daylight = r.offsetAfter.totalSeconds > r.standardOffset.totalSeconds
                val kind = if (daylight) "DAYLIGHT" else "STANDARD"
                out += "BEGIN:$kind"
                out += "DTSTART:" + LOCAL.format(t.dateTimeBefore)
                out += "TZOFFSETFROM:${off(r.offsetBefore)}"; out += "TZOFFSETTO:${off(r.offsetAfter)}"
                out += "RRULE:" + yearly(r)
                out += "END:$kind"
            }
        }
        out += "END:VTIMEZONE"
        return out
    }

    private fun yearly(r: ZoneOffsetTransitionRule): String {
        val m = r.month.value
        val d = r.dayOfMonthIndicator
        val dow = r.dayOfWeek
        return when {
            dow == null -> "FREQ=YEARLY;BYMONTH=$m;BYMONTHDAY=$d"
            d == -1 -> "FREQ=YEARLY;BYMONTH=$m;BYDAY=-1${DAY_CODES.getValue(dow)}"
            d < 0 -> "FREQ=YEARLY;BYMONTH=$m;BYDAY=${DAY_CODES.getValue(dow)};BYMONTHDAY=" + ((d - 6)..d).joinToString(",")
            else -> "FREQ=YEARLY;BYMONTH=$m;BYDAY=${DAY_CODES.getValue(dow)};BYMONTHDAY=" + (d..d + 6).joinToString(",")
        }
    }
}
