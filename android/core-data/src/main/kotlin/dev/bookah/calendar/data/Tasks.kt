package dev.bookah.calendar.data

import biweekly.Biweekly
import biweekly.component.VTodo
import biweekly.io.TimezoneInfo
import biweekly.property.DateOrDateTimeProperty
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Nextcloud task (VTODO) as the calendar shows it. The only write this app makes to a task is the tick of a plain one and its undo
 * ([TaskWriter]); everything else is changed in BookahTasks, and only BookahTasks rings for tasks. Times are UTC milliseconds like [Instance]: a date-only value is UTC midnight of its date
 * ([startAllDay] / [dueAllDay] tell the screens to read it as a date).
 */
data class TaskItem(
    val calendarId: Long,
    val uid: String,
    val objectHref: String,
    val title: String,
    val notes: String?,
    val start: Long?,
    val startAllDay: Boolean,
    val due: Long?,
    val dueAllDay: Boolean,
    /** Completed or cancelled (STATUS or a COMPLETED time, as in BookahTasks). Finished tasks are not shown. */
    val done: Boolean,
    /** 1 (highest) to 9 (lowest), 0 = none (RFC 5545). */
    val priority: Int,
    /** The UID of the task this one is a subtask of (RELATED-TO with no type or type PARENT), or null. */
    val parentUid: String?,
    val url: String?,
) {
    /** What puts the task on a day: its deadline, else its start; null = no date (the Tasks page lists it). */
    val placedAt: Long? get() = due ?: start
    val placedAllDay: Boolean get() = if (due != null) dueAllDay else startAllDay
    val key: String get() = "task|$calendarId|$objectHref|$uid"
}

/**
 * Reads the tasks of stored objects, with a cache by the object's key (href + ETag, as for the [Expander]): a task is read once per change.
 * An object that cannot be read gives no task and is counted, never fatal; only class names are logged, never task text.
 */
class TaskReader {
    private val cache = ConcurrentHashMap<String, Pair<String, List<TaskItem>>>()

    fun read(sources: List<ObjectSource>, zone: ZoneId): List<TaskItem> {
        val out = ArrayList<TaskItem>()
        for (s in sources) {
            val hit = cache[s.cacheKey]?.takeIf { it.first == zone.id }?.second
                ?: parse(s.calendarId, s.href, s.ics, zone).also { cache[s.cacheKey] = zone.id to it }
            out.addAll(hit)
        }
        // one row per key: two stored copies of the same item would otherwise crash a list that is keyed by task
        return out.distinctBy { it.key }
    }

    /** Drops cached readings of objects that are no longer in the store. */
    fun retainOnly(keys: Set<String>) { cache.keys.retainAll(keys) }

    companion object {
        /** How many objects or tasks could not be read since the app started (never what was in them). */
        val failures = AtomicInteger(0)

        private val END_OF_DAY: LocalTime = LocalTime.of(23, 59)

        /** All open and finished tasks in one stored object; completed copies of a repeating task (RECURRENCE-ID) are left out. */
        fun parse(calendarId: Long, href: String, ics: String, zone: ZoneId): List<TaskItem> {
            val cal = runCatching { Biweekly.parse(ics).first() }.onFailure { report("parse", it) }.getOrNull() ?: return emptyList()
            return cal.todos.mapNotNull { t -> runCatching { toItem(t, calendarId, href, zone, cal.timezoneInfo) }.onFailure { report("read", it) }.getOrNull() }
                .distinctBy { it.key }          // two VTODOs with one UID (and no RECURRENCE-ID): the first one is shown
        }

        private fun toItem(t: VTodo, calendarId: Long, href: String, zone: ZoneId, tzi: TimezoneInfo?): TaskItem? {
            if (t.recurrenceId != null) return null
            val uid = t.uid?.value?.trim()?.ifEmpty { null } ?: return null
            val status = t.status?.value?.uppercase()
            // the same rule as BookahTasks (Task.kt): STATUS and the COMPLETED time decide; PERCENT-COMPLETE alone does not finish a task
            val done = status == "COMPLETED" || status == "CANCELLED" || t.completed?.value != null
            // BookahTasks writes a start without a time beside a timed deadline as 00:00, and a deadline without a time beside a timed start as
            // 23:59 (the server wants DTSTART and DUE of one kind). Those are read back as the dates they were.
            // A date that cannot be read is left out on its own: the task stays, placed by the other date (or undated).
            val rawStart = runCatching { localOf(t.dateStart, zone, tzi) }.onFailure { report("start", it) }.getOrNull()
            val rawDue = runCatching { localOf(t.dateDue, zone, tzi) }.onFailure { report("deadline", it) }.getOrNull()
            val start = if (rawStart?.second == LocalTime.MIDNIGHT && rawDue?.second != null) rawStart.first to null else rawStart
            val due = if (rawDue?.second == END_OF_DAY && rawStart?.second != null) rawDue.first to null else rawDue
            val parent = t.relatedTo.firstOrNull { r -> r.relationshipType?.value.let { it == null || it.equals("PARENT", true) } }?.value?.trim()?.ifEmpty { null }
            return TaskItem(
                calendarId = calendarId, uid = uid, objectHref = href,
                title = t.summary?.value?.trim().orEmpty(),
                notes = t.description?.value?.replace("\r\n", "\n")?.ifBlank { null },
                start = start?.let { millisOf(it, zone) }, startAllDay = start != null && start.second == null,
                due = due?.let { millisOf(it, zone) }, dueAllDay = due != null && due.second == null,
                done = done, priority = (t.priority?.value ?: 0).coerceIn(0, 9), parentUid = parent,
                url = t.url?.value?.trim()?.ifBlank { null },
            )
        }

        /** A date-only value: UTC midnight of the date. A timed one: that moment, its clock time read on the phone. */
        private fun millisOf(v: Pair<LocalDate, LocalTime?>, zone: ZoneId): Long =
            if (v.second == null) v.first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            else LocalDateTime.of(v.first, v.second).atZone(zone).toInstant().toEpochMilli()

        /**
         * The date and (if any) clock time on the phone. UTC and named zones are converted (a zone Java knows is used directly, so an item needs no
         * VTIMEZONE of its own); a floating time is the same clock time on any phone. The same reading as BookahTasks' TaskParser.
         */
        private fun localOf(p: DateOrDateTimeProperty?, zone: ZoneId, tzi: TimezoneInfo?): Pair<LocalDate, LocalTime?>? {
            val d = p?.value ?: return null
            val c = d.rawComponents
                ?: return Instant.ofEpochMilli(d.time).atZone(if (d.hasTime()) zone else ZoneOffset.UTC).let { z -> z.toLocalDate() to (if (d.hasTime()) z.toLocalTime().withSecond(0).withNano(0) else null) }
            val date = LocalDate.of(c.year, c.month, c.date)
            if (!c.hasTime()) return date to null
            val assigned = tzi?.getTimezone(p)
            val tzid = assigned?.globalId ?: assigned?.timeZone?.id ?: p.parameters.timezoneId
            if (c.isUtc || tzid != null) {
                val named = tzid?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                val instant = if (named != null) LocalDateTime.of(c.year, c.month, c.date, c.hour, c.minute, c.second).atZone(named).toInstant() else Instant.ofEpochMilli(d.time)
                val z = instant.atZone(zone)
                return z.toLocalDate() to z.toLocalTime().withSecond(0).withNano(0)
            }
            return date to LocalTime.of(c.hour, c.minute)
        }

        private fun report(stage: String, error: Throwable) {
            if (failures.incrementAndGet() > 3) return
            var root: Throwable = error
            while (root.cause != null && root.cause !== root) root = root.cause!!
            val frames = root.stackTrace.take(4).joinToString(" < ") { "${it.className}.${it.methodName}" }
            runCatching { android.util.Log.w("BookahCalendarTasks","$stage failed: ${error.javaClass.name}, root ${root.javaClass.name} at $frames") }
        }
    }
}
