package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Expander
import dev.bookah.calendar.data.Instance
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import dev.bookah.calendar.data.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of the Schedule: its events, all-day ones first, then by start time; and the open tasks placed on it (read-only, see [groupTasksByDay]). */
data class DayItems(val date: LocalDate, val events: List<Instance>, val tasks: List<TaskItem> = emptyList())

/**
 * Puts the OPEN tasks on days: on the day of the deadline, else of the start (a date-only value on its date, a timed one on its local day).
 * Finished tasks, tasks without any date and tasks outside [from]..[toExclusive] are left out. Date-only first, then by time, then by title.
 */
fun groupTasksByDay(tasks: List<TaskItem>, from: LocalDate, toExclusive: LocalDate, zone: ZoneId, today: LocalDate? = null): Map<LocalDate, List<TaskItem>> {
    val byDay = HashMap<LocalDate, MutableList<TaskItem>>()
    for (t in tasks) {
        if (t.done) continue
        val at = t.placedAt ?: continue
        var day = if (t.placedAllDay) LocalDate.ofEpochDay(Math.floorDiv(at, DAY_MS)) else Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        // overdue (the deadline's day has passed) folds onto today, as in BookahTasks; a start alone is never overdue
        if (today != null && t.due != null && day.isBefore(today)) day = today
        if (day.isBefore(from) || !day.isBefore(toExclusive)) continue
        byDay.getOrPut(day) { ArrayList() }.add(t)
    }
    return byDay.mapValues { (_, list) -> list.sortedWith(compareByDescending<TaskItem> { it.placedAllDay }.thenBy { it.placedAt }.thenBy { it.title.lowercase() }) }
}

/** The days with their tasks filled in. */
fun attachTasks(days: List<DayItems>, tasksByDay: Map<LocalDate, List<TaskItem>>): List<DayItems> =
    if (tasksByDay.isEmpty()) days else days.map { d -> tasksByDay[d.date]?.let { d.copy(tasks = it) } ?: d }

/** Open tasks with neither a start nor a deadline (the Tasks page), by list, then by title. */
fun undatedTasks(tasks: List<TaskItem>): List<TaskItem> =
    tasks.filter { !it.done && it.placedAt == null }.sortedWith(compareBy<TaskItem> { it.calendarId }.thenBy { it.title.lowercase() })

/** "14:05" for a task placed by a timed value; null when it is placed by a date (or has none). */
fun taskTimeText(t: TaskItem, zone: ZoneId): String? {
    val at = t.placedAt ?: return null
    return if (t.placedAllDay) null else TIME_FMT.format(Instant.ofEpochMilli(at).atZone(zone))
}

/** An open task whose deadline has passed (a date-only deadline is late from the next day on). A start alone is never late. */
fun isOverdue(t: TaskItem, nowMs: Long, today: LocalDate): Boolean {
    if (t.done) return false
    val due = t.due ?: return false
    return if (t.dueAllDay) LocalDate.ofEpochDay(Math.floorDiv(due, DAY_MS)).isBefore(today) else due < nowMs
}

/**
 * Puts instances on the days they cover. A timed event belongs to every local day it overlaps; an all-day event
 * (stored as UTC midnight dates) belongs to the dates it names, whatever the phone's time zone.
 * Every day from [from] to [toExclusive] is returned, empty days included (the Schedule shows "a quiet day").
 */
fun groupByDay(instances: List<Instance>, from: LocalDate, toExclusive: LocalDate, zone: ZoneId): List<DayItems> {
    val byDay = HashMap<LocalDate, MutableList<Instance>>()
    for (e in instances) {
        val first: LocalDate
        val lastInclusive: LocalDate
        if (e.allDay) {
            first = LocalDate.ofEpochDay(Math.floorDiv(e.start, DAY_MS))
            lastInclusive = LocalDate.ofEpochDay(Math.floorDiv(maxOf(e.end - 1, e.start), DAY_MS))
        } else {
            first = Instant.ofEpochMilli(e.start).atZone(zone).toLocalDate()
            // an event ending exactly at midnight does not belong to the next day
            lastInclusive = Instant.ofEpochMilli(maxOf(e.end - 1, e.start)).atZone(zone).toLocalDate()
        }
        var d = maxOf(first, from)
        val stop = minOf(lastInclusive, toExclusive.minusDays(1))
        while (!d.isAfter(stop)) {
            byDay.getOrPut(d) { ArrayList() }.add(e)
            d = d.plusDays(1)
        }
    }
    val out = ArrayList<DayItems>()
    var d = from
    while (d.isBefore(toExclusive)) {
        val list = byDay[d].orEmpty().sortedWith(compareByDescending<Instance> { it.allDay }.thenBy { it.start }.thenBy { it.title })
        out.add(DayItems(d, list))
        d = d.plusDays(1)
    }
    return out
}

private const val DAY_MS = 86_400_000L

/**
 * A Google event whose UID also exists in a Nextcloud calendar is a copy: it is left out, so nothing shows twice and Nextcloud stays
 * the source of truth. Checked over every Nextcloud object, also in calendars that are hidden right now. Returns what remains and
 * how many Google events were left out (Settings shows that number).
 */
fun dropGoogleDuplicates(objects: List<StoredObject>, calendars: List<CalendarInfo>): Pair<List<StoredObject>, Int> {
    val source = calendars.associate { it.id to it.source }
    val nextcloudUids = HashSet<String>()
    for (o in objects) if (source[o.calendarId] == Source.Nextcloud) Expander.uidOf(o.ics)?.let { nextcloudUids.add(it) }
    var hidden = 0
    val kept = objects.filter { o ->
        val dup = source[o.calendarId] == Source.Google && Expander.uidOf(o.ics)?.let { it in nextcloudUids } == true
        if (dup) hidden++
        !dup
    }
    return kept to hidden
}

/**
 * What the expander reads (for the screens and for the reminders): the items of shown calendars that hold events. A task-only list is
 * never handed over, so a task can never become an event or ring here (only BookahTasks rings for tasks).
 */
fun eventSources(objects: List<StoredObject>, calendars: List<CalendarInfo>): List<dev.bookah.calendar.data.ObjectSource> {
    val shown = calendars.filter { it.visible && it.kind.holdsEvents }.associateBy { it.id }
    return objects.mapNotNull { o ->
        val cal = shown[o.calendarId] ?: return@mapNotNull null
        dev.bookah.calendar.data.ObjectSource(o.calendarId, o.href, o.ics, o.href + "|" + o.etag, readOnly = !cal.editable)
    }
}

private val TIME_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm")   // 24-hour clock, as in the design spec

/** "08:30 – 09:00"; a multi-day event shows the part that falls on [day] (00:00 at the start, 24:00 at the end). */
fun timeRangeText(e: Instance, day: LocalDate, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(e.start).atZone(zone)
    val en = Instant.ofEpochMilli(e.end).atZone(zone)
    val startText = if (s.toLocalDate() == day) TIME_FMT.format(s) else "00:00"
    val endText = if (en.toLocalDate() == day) TIME_FMT.format(en) else "24:00"
    return if (e.end <= e.start) startText else "$startText – $endText"
}

/** One search result: the event and the day it is shown under (the first loaded day of a multi-day event). */
data class SearchHit(val day: LocalDate, val event: Instance)

/** Lower case, without accents or Greek tonos, final sigma as sigma: "Καφές" matches "καφες". */
fun searchKey(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]+"), "").replace('ς', 'σ')

/**
 * Events of the loaded days whose title, place or notes contain every word of [query] (any order). Coming events first, soonest first;
 * then past ones, most recent first. At most [limit]. An empty query finds nothing.
 */
fun searchHits(days: List<DayItems>, query: String, today: LocalDate, limit: Int = 200): List<SearchHit> {
    val words = searchKey(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val seen = HashSet<Triple<String, Long, Long>>()
    val hits = ArrayList<SearchHit>()
    for (d in days) for (e in d.events) {
        if (!seen.add(Triple(e.objectHref, e.originalStart, e.start))) continue        // a multi-day event is listed once
        val hay = searchKey(listOfNotNull(e.title, e.location, e.description).joinToString("\n"))
        if (words.all { it in hay }) hits.add(SearchHit(d.date, e))
    }
    val (coming, past) = hits.partition { !it.day.isBefore(today) }
    return (coming.sortedBy { it.event.start } + past.sortedByDescending { it.event.start }).take(limit)
}
