package dev.bookah.calendar

import dev.bookah.calendar.data.Instance
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One notification to show at [triggerAtMs]. [key] is stable for the same event occurrence and reminder, so a reschedule updates instead of duplicating. */
data class Reminder(
    val key: String, val triggerAtMs: Long, val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean, val location: String?,
)

/**
 * Decides which reminders the phone should have scheduled (docs/phase-4-design.md 2.8). Reminders live in the events themselves (VALARM); the phone
 * only turns the ones in the next [HORIZON_DAYS] days into alarms, at most [MAX_SCHEDULED] (the nearest ones: Android allows only a limited
 * number of alarms per app, and each reschedule or fired alarm pulls the next ones in).
 */
object AlarmPlanner {
    const val HORIZON_DAYS = 14
    const val MAX_SCHEDULED = 40
    private const val DAY_MS = 86_400_000L

    fun plan(instances: List<Instance>, nowMs: Long, zone: ZoneId): List<Reminder> {
        val horizon = nowMs + HORIZON_DAYS * DAY_MS
        val out = ArrayList<Reminder>()
        for (e in instances) {
            if (e.alarmMinutes.isEmpty()) continue
            // an all-day event is stored as a UTC date; its reminders count from midnight of that day in the phone's own zone
            val startMs = if (e.allDay) LocalDate.ofEpochDay(Math.floorDiv(e.start, DAY_MS)).atStartOfDay(zone).toInstant().toEpochMilli() else e.start
            val endMs = if (e.allDay) startMs + maxOf(e.end - e.start, DAY_MS) else e.end
            for (minutes in e.alarmMinutes.distinct()) {
                val at = startMs - minutes * 60_000L
                if (at <= nowMs || at > horizon) continue
                out.add(Reminder("${e.objectHref}|${e.originalStart}|$minutes", at, e.title, startMs, endMs, e.allDay, e.location))
            }
        }
        return out.distinctBy { it.key }.sortedWith(compareBy({ it.triggerAtMs }, { it.key })).take(MAX_SCHEDULED)
    }

    @Suppress("unused")
    internal fun instant(ms: Long): Instant = Instant.ofEpochMilli(ms)
}
