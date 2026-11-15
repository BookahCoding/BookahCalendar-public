package dev.bookah.calendar

import dev.bookah.calendar.data.Instance
import java.time.LocalDate
import java.time.ZoneId

/** One timed event placed in one day column of the time grid. Minutes count from midnight of that day (0 to 1440). */
data class Placed(val event: Instance, val startMin: Int, val endMin: Int, val column: Int, val columns: Int)

/** The most the grid ever covers: 24 hours. */
const val MINUTES_PER_DAY = 1440

/**
 * Places the timed events of one [day] in a grid column. Events that overlap share the width (side by side, like in Google Calendar);
 * events that do not overlap use the full width. All-day events are not placed here (they go in the strip above the grid).
 *
 * - An event that starts before or ends after the day is clipped to the day.
 * - Every event is drawn at least [minMinutes] tall, so a 5-minute event is still tappable and shows a line of text; for the overlap
 *   test the drawn height counts, so such short events do not hide each other.
 */
fun layoutDay(events: List<Instance>, day: LocalDate, zone: ZoneId, minMinutes: Int = 25): List<Placed> {
    val dayStartMs = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndMs = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    class Item(val e: Instance, val start: Int, val end: Int)

    val items = events.filter { !it.allDay && it.end > dayStartMs && it.start < dayEndMs || (!it.allDay && it.start == it.end && it.start in dayStartMs until dayEndMs) }
        .map { e ->
            val s = ((maxOf(e.start, dayStartMs) - dayStartMs) / 60_000L).toInt().coerceIn(0, MINUTES_PER_DAY - 1)
            val en = ((minOf(e.end, dayEndMs) - dayStartMs) / 60_000L).toInt().coerceIn(s + 1, MINUTES_PER_DAY)
            val drawnEnd = maxOf(en, minOf(s + minMinutes, MINUTES_PER_DAY))
            Item(e, s, maxOf(drawnEnd, en))
        }
        .sortedWith(compareBy<Item> { it.start }.thenByDescending { it.end - it.start }.thenBy { it.e.title })

    val out = ArrayList<Placed>()
    var cluster = ArrayList<Item>()
    var clusterEnd = -1
    fun flush() {
        if (cluster.isEmpty()) return
        // greedy columns inside one cluster of mutually chained overlaps
        val colEnds = ArrayList<Int>()
        val assigned = ArrayList<Pair<Item, Int>>()
        for (it in cluster) {
            var col = colEnds.indexOfFirst { end -> end <= it.start }
            if (col < 0) { colEnds.add(it.end); col = colEnds.size - 1 } else colEnds[col] = it.end
            assigned.add(it to col)
        }
        val columns = colEnds.size
        for ((it, col) in assigned) out.add(Placed(it.e, it.start, it.end, col, columns))
        cluster = ArrayList()
    }
    for (it in items) {
        if (cluster.isNotEmpty() && it.start >= clusterEnd) { flush(); clusterEnd = -1 }
        cluster.add(it)
        clusterEnd = maxOf(clusterEnd, it.end)
    }
    flush()
    return out
}

/** The all-day events of [day] for the strip above the grid (a multi-day all-day event appears on each of its days). */
fun allDayFor(events: List<Instance>, day: LocalDate): List<Instance> {
    val ms = day.toEpochDay() * 86_400_000L          // all-day events are stored as UTC dates
    return events.filter { it.allDay && it.start <= ms && it.end > ms }.sortedBy { it.title }
}
