package dev.bookah.calendar

import dev.bookah.calendar.data.Instance
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GroupByDayTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val from = LocalDate.of(2026, 11, 1)
    private val to = LocalDate.of(2026, 11, 8)

    private fun inst(title: String, start: Long, end: Long, allDay: Boolean = false) =
        Instance(1, title, "h", title, null, null, start, end, allDay, null, null, emptyList(), false)

    private fun ms(date: String, time: String = "00:00") =
        java.time.LocalDateTime.parse("${date}T$time").atZone(athens).toInstant().toEpochMilli()

    private fun utcMidnight(date: String) = LocalDate.parse(date).toEpochDay() * 86_400_000L

    @Test fun everyDayIsReturnedEvenWhenEmpty() {
        val days = groupByDay(emptyList(), from, to, athens)
        assertEquals(7, days.size)
        assertEquals(from, days.first().date)
        assertEquals(true, days.all { it.events.isEmpty() })
    }

    @Test fun timedEventLandsOnItsLocalDay() {
        // 23:30 Athens is still the 3rd locally although it is already the 3rd 21:30 UTC
        val days = groupByDay(listOf(inst("late", ms("2026-11-03", "23:30"), ms("2026-11-03", "23:45"))), from, to, athens)
        assertEquals(listOf("late"), days.first { it.date == LocalDate.of(2026, 11, 3) }.events.map { it.title })
        assertEquals(1, days.sumOf { it.events.size })
    }

    @Test fun eventEndingAtMidnightDoesNotSpillOver() {
        val days = groupByDay(listOf(inst("x", ms("2026-11-03", "22:00"), ms("2026-11-04"))), from, to, athens)
        assertEquals(1, days.sumOf { it.events.size })
    }

    @Test fun multiDayAllDayEventCoversEachNamedDate() {
        val days = groupByDay(listOf(inst("trip", utcMidnight("2026-11-02"), utcMidnight("2026-11-05"), allDay = true)), from, to, athens)
        val on = days.filter { it.events.isNotEmpty() }.map { it.date.dayOfMonth }
        assertEquals(listOf(2, 3, 4), on)
    }

    @Test fun allDayComesFirstThenByStart() {
        val list = listOf(
            inst("b", ms("2026-11-03", "09:00"), ms("2026-11-03", "10:00")),
            inst("a", ms("2026-11-03", "08:00"), ms("2026-11-03", "08:30")),
            inst("day", utcMidnight("2026-11-03"), utcMidnight("2026-11-04"), allDay = true),
        )
        val titles = groupByDay(list, from, to, athens).first { it.date.dayOfMonth == 3 }.events.map { it.title }
        assertEquals(listOf("day", "a", "b"), titles)
    }

    @Test fun eventsOutsideTheWindowAreIgnored() {
        val days = groupByDay(listOf(inst("old", ms("2026-10-20", "10:00"), ms("2026-10-20", "11:00"))), from, to, athens)
        assertEquals(0, days.sumOf { it.events.size })
    }
}
