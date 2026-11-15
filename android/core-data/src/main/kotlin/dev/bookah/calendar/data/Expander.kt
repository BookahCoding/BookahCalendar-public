package dev.bookah.calendar.data

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.util.ICalDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** One calendar object (all VEVENTs sharing a UID) handed to the expander. [cacheKey] changes whenever [ics] changes. */
data class ObjectSource(val calendarId: Long, val href: String, val ics: String, val cacheKey: String, val readOnly: Boolean)

/** How an event repeats, kept as plain values so the screens can describe it in either language. */
data class RepeatInfo(val frequency: String, val interval: Int, val byDay: List<String>, val count: Int?, val untilMillis: Long?)

/**
 * One occurrence of an event. Times are UTC milliseconds; an all-day event starts at UTC midnight of its first day and
 * ends at UTC midnight after its last day ([allDay] tells the screens to read them as dates, not instants).
 */
data class Instance(
    val calendarId: Long,
    val uid: String,
    val objectHref: String,
    val title: String,
    val location: String?,
    val description: String?,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val zoneId: String?,
    val repeat: RepeatInfo?,
    /** "minutes before the start" for each VALARM that is relative to the start. */
    val alarmMinutes: List<Int>,
    val readOnly: Boolean,
    /** The slot this occurrence has in its series (the RECURRENCE-ID of an edited occurrence, which may have been moved); equal to [start] otherwise. Series changes name occurrences by this. */
    val originalStart: Long = start,
    /** The event's own colour ("#RRGGBB" from its COLOR property), or null: then the calendar's colour is used. */
    val colorHex: String? = null,
) {
    val key: String get() = "$calendarId|$uid|$start"
}

/**
 * Reads calendar objects and lists their occurrences in a time range, repeats included (RRULE, EXDATE, edited instances).
 * Built on biweekly 0.6.8, which matched 1,112 of 1,112 occurrences Nextcloud itself expanded for the real calendar (Phase 0).
 */
class Expander {
    /** Parsed objects by cache key; [BROKEN] marks an object that could not be read (a map cannot hold null). */
    private val parsed = ConcurrentHashMap<String, Any>()

    private fun parse(src: ObjectSource): ICalendar? {
        val hit = parsed.getOrPut(src.cacheKey) { runCatching { Biweekly.parse(src.ics).first() }.onFailure { report("parse", it) }.getOrNull() ?: BROKEN }
        return hit as? ICalendar
    }

    /** How many objects could not be read or expanded since the app started, shown in Settings (never what was in them). */
    val failures = java.util.concurrent.atomic.AtomicInteger(0)

    /** An object that cannot be read is skipped, never fatal. Only the exception class and its top frame are logged: no calendar text. */
    private fun report(stage: String, error: Throwable) {
        if (failures.incrementAndGet() > 3) return        // enough to diagnose; no flood
        var root: Throwable = error
        while (root.cause != null && root.cause !== root) root = root.cause!!
        val frames = root.stackTrace.take(4).joinToString(" < ") { "${it.className}.${it.methodName}" }
        runCatching { android.util.Log.w("BookahExpander", "$stage failed: ${error.javaClass.name}, root ${root.javaClass.name} at $frames") }
    }

    /** Drops parsed objects that are no longer in the store. */
    fun retainOnly(keys: Set<String>) { parsed.keys.retainAll(keys) }

    fun expand(sources: List<ObjectSource>, fromMs: Long, toMs: Long): List<Instance> {
        val out = ArrayList<Instance>()
        for (s in sources) {
            val cal = parse(s) ?: continue
            runCatching { expandOne(s, cal, fromMs, toMs, out) }.onFailure { report("expand", it) }
        }
        return out
    }

    private fun timeOf(d: ICalDate): Pair<Long, Boolean> =
        if (d.hasTime()) d.time to false
        else {
            val c = d.rawComponents
            LocalDate.of(c.year, c.month, c.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        }

    private fun expandOne(src: ObjectSource, cal: ICalendar, fromMs: Long, toMs: Long, out: MutableList<Instance>) {
        val events: List<VEvent> = cal.events.filter { it.dateStart != null && it.uid != null }
        if (events.isEmpty()) return
        val uid = events.first().uid.value
        // the same conversion as for the series' own occurrences: an all-day RECURRENCE-ID is a date, i.e. UTC midnight (its raw `.time` would be
        // local midnight of the machine's zone, which never matches in a zone like Athens and showed an edited all-day occurrence twice)
        val overrideStarts = events.filter { it.recurrenceId != null }.map { timeOf(it.recurrenceId.value).first }.toSet()
        val tzi = cal.timezoneInfo
        for (e in events) {
            if (e.status?.isCancelled == true) continue
            val (start, allDay) = timeOf(e.dateStart.value)
            val dur = when {
                e.dateEnd != null -> maxOf(0L, timeOf(e.dateEnd.value).first - start)
                e.duration != null -> e.duration.value.toMillis()
                allDay -> DAY_MS
                else -> 0L
            }
            val zone = if (allDay) null else tzi.getTimezone(e.dateStart)?.timeZone
            fun make(s: Long, original: Long = s) = Instance(
                calendarId = src.calendarId, uid = uid, objectHref = src.href,
                title = e.summary?.value?.ifBlank { null } ?: "", location = e.location?.value?.ifBlank { null },
                description = e.description?.value?.replace("\r\n", "\n")?.ifBlank { null },     // one kind of line break for display and for the form
                start = s, end = s + dur, allDay = allDay, zoneId = zone?.id, repeat = repeatInfo(e), alarmMinutes = alarms(e), readOnly = src.readOnly, originalStart = original,
                colorHex = EventColors.resolve(e.getProperty(biweekly.property.Color::class.java)?.value),
            )
            fun inRange(s: Long) = s < toMs && (s + dur > fromMs || (dur == 0L && s >= fromMs))
            if (e.recurrenceId != null || e.recurrenceRule == null) {
                if (inRange(start)) out.add(make(start, e.recurrenceId?.let { r -> timeOf(r.value).first } ?: start))
                continue
            }
            val excluded = HashSet<Long>()
            for (x in e.exceptionDates) for (v in x.values) excluded.add(timeOf(v).first)
            val tz: TimeZone = if (allDay) UTC else (zone ?: UTC)
            val it = e.recurrenceRule.value.getDateIterator(Date(start), tz)
            it.advanceTo(Date(fromMs - dur - DAY_MS))
            var guard = 0
            while (it.hasNext() && guard++ < MAX_PER_EVENT) {
                val s = it.next().time
                if (s >= toMs) break
                if (s in excluded || s in overrideStarts) continue
                if (inRange(s)) out.add(make(s))
            }
        }
    }

    private fun repeatInfo(e: VEvent): RepeatInfo? {
        val r = e.recurrenceRule?.value ?: return null
        return RepeatInfo(
            frequency = r.frequency?.name ?: "", interval = r.interval ?: 1,
            byDay = r.byDay.map { (if (it.num != null) it.num.toString() else "") + it.day.abbr },
            count = r.count, untilMillis = r.until?.time,
        )
    }

    private fun alarms(e: VEvent): List<Int> = e.alarms.mapNotNull { a ->
        val t = a.trigger ?: return@mapNotNull null
        val d = t.duration ?: return@mapNotNull null
        if (t.related != null && t.related != biweekly.parameter.Related.START) return@mapNotNull null
        val ms = d.toMillis()
        if (ms > 0) null else (-ms / 60_000L).toInt()
    }.distinct().sortedDescending()

    companion object {
        private val BROKEN = Any()
        const val DAY_MS = 86_400_000L
        private const val MAX_PER_EVENT = 5_000
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

        /** Splits a whole-feed .ics into one calendar object per UID (the feed's VTIMEZONEs are copied into each). */
        fun splitFeed(text: String): List<Pair<String, String>> {
            val nl = "\r\n"
            val tz = StringBuilder()
            val events = LinkedHashMap<String, StringBuilder>()
            var mode = 0
            val buf = StringBuilder()
            for (l in text.split("\r\n", "\n")) {
                when {
                    l == "BEGIN:VTIMEZONE" -> { mode = 1; tz.append(l).append(nl) }
                    l == "END:VTIMEZONE" -> { tz.append(l).append(nl); mode = 0 }
                    l == "BEGIN:VEVENT" -> { mode = 2; buf.setLength(0); buf.append(l).append(nl) }
                    l == "END:VEVENT" -> {
                        buf.append(l).append(nl)
                        uidOf(buf.toString())?.let { events.getOrPut(it) { StringBuilder() }.append(buf) }
                        mode = 0
                    }
                    mode == 1 -> tz.append(l).append(nl)
                    mode == 2 -> buf.append(l).append(nl)
                }
            }
            return events.map { (uid, body) -> uid to ("BEGIN:VCALENDAR" + nl + "VERSION:2.0" + nl + "PRODID:-//BookahCalendar//EN" + nl + tz + body + "END:VCALENDAR" + nl) }
        }

        private val UID = Regex("(?m)^UID:(.*?)\\r?$")

        /** The UID of an iCalendar object without parsing it (used to match Google copies against Nextcloud). */
        fun uidOf(ics: String): String? = UID.find(ics)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

        fun instantOf(ms: Long): Instant = Instant.ofEpochMilli(ms)
    }
}
