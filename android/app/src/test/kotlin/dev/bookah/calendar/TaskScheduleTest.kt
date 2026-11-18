package dev.bookah.calendar

import dev.bookah.calendar.data.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Where open tasks appear: on the day of their deadline (else their start); without a date on the Tasks page. Generated tasks only. */
class TaskScheduleTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val from = LocalDate.of(2026, 11, 1)
    private val to = LocalDate.of(2026, 11, 30)

    private fun utcMidnight(date: String) = LocalDate.parse(date).toEpochDay() * 86_400_000L
    private fun ms(date: String, time: String) = LocalDateTime.parse("${date}T$time").atZone(athens).toInstant().toEpochMilli()

    private fun task(
        title: String, due: Long? = null, dueAllDay: Boolean = false, start: Long? = null, startAllDay: Boolean = false,
        done: Boolean = false, cal: Long = 1,
    ) = TaskItem(cal, "uid-$title", "/c/$cal/$title.ics", title, null, start, startAllDay, due, dueAllDay, done, 0, null, null)

    @Test fun aDateOnlyDeadlineIsOnItsDateInAnyZone() {
        val t = task("pay", due = utcMidnight("2026-11-18"), dueAllDay = true)
        assertEquals(listOf("pay"), groupTasksByDay(listOf(t), from, to, athens)[LocalDate.of(2026, 11, 18)]?.map { it.title })
        assertEquals(listOf("pay"), groupTasksByDay(listOf(t), from, to, ZoneId.of("America/New_York"))[LocalDate.of(2026, 11, 18)]?.map { it.title })
    }

    @Test fun aTimedDeadlineIsOnItsLocalDay() {
        // 23:30 in Athens is still the 18th there, although it is 21:30 UTC
        val t = task("late", due = ms("2026-11-18", "23:30"))
        val byDay = groupTasksByDay(listOf(t), from, to, athens)
        assertEquals(setOf(LocalDate.of(2026, 11, 18)), byDay.keys)
    }

    @Test fun theDeadlineWinsOverTheStartAndTheStartIsUsedWithoutOne() {
        val both = task("both", start = utcMidnight("2026-11-10"), startAllDay = true, due = utcMidnight("2026-11-12"), dueAllDay = true)
        val startOnly = task("begin", start = ms("2026-11-11", "09:00"))
        val byDay = groupTasksByDay(listOf(both, startOnly), from, to, athens)
        assertEquals(listOf("both"), byDay[LocalDate.of(2026, 11, 12)]?.map { it.title })
        assertEquals(listOf("begin"), byDay[LocalDate.of(2026, 11, 11)]?.map { it.title })
        assertNull(byDay[LocalDate.of(2026, 11, 10)])
    }

    @Test fun finishedAndUndatedTasksAreNotOnAnyDay() {
        val byDay = groupTasksByDay(listOf(task("done", due = utcMidnight("2026-11-05"), dueAllDay = true, done = true), task("someday")), from, to, athens)
        assertTrue(byDay.isEmpty())
    }

    @Test fun anOverdueTaskFoldsOntoTodayEvenWhenItIsOlderThanTheLoadedDays() {
        val today = LocalDate.of(2026, 11, 18)
        val late = task("late", due = utcMidnight("2026-11-10"), dueAllDay = true)
        val ancient = task("ancient", due = utcMidnight("2025-01-02"), dueAllDay = true)
        val startOnly = task("began", start = ms("2026-11-12", "09:00"))              // a start alone is never overdue: stays on its day
        val doneOne = task("done", due = utcMidnight("2026-11-10"), dueAllDay = true, done = true)
        val byDay = groupTasksByDay(listOf(late, ancient, startOnly, doneOne), from, to, athens, today)
        assertEquals(listOf("ancient", "late"), byDay[today]?.map { it.title })
        assertEquals(listOf("began"), byDay[LocalDate.of(2026, 11, 12)]?.map { it.title })
        assertNull(byDay[LocalDate.of(2026, 11, 10)])
    }

    @Test fun tasksOutsideTheLoadedDaysAreLeftOut() {
        val byDay = groupTasksByDay(listOf(task("old", due = utcMidnight("2026-10-20"), dueAllDay = true), task("far", due = ms("2026-12-01", "10:00"))), from, to, athens)
        assertTrue(byDay.isEmpty())
    }

    @Test fun dateOnlyTasksComeFirstThenByTimeThenByTitle() {
        val day = "2026-11-18"
        val list = listOf(
            task("c", due = ms(day, "15:00")), task("a", due = ms(day, "09:00")),
            task("z", due = utcMidnight(day), dueAllDay = true), task("b", due = utcMidnight(day), dueAllDay = true),
        )
        assertEquals(listOf("b", "z", "a", "c"), groupTasksByDay(list, from, to, athens)[LocalDate.parse(day)]?.map { it.title })
    }

    @Test fun theDaysCarryTheirTasks() {
        val days = groupByDay(emptyList(), from, to, athens)
        val withTasks = attachTasks(days, groupTasksByDay(listOf(task("pay", due = utcMidnight("2026-11-18"), dueAllDay = true)), from, to, athens))
        assertEquals(listOf("pay"), withTasks.first { it.date == LocalDate.of(2026, 11, 18) }.tasks.map { it.title })
        assertEquals(1, withTasks.sumOf { it.tasks.size })
        assertEquals(days.size, withTasks.size)
    }

    @Test fun undatedOpenTasksAreListedByListThenTitle() {
        val list = listOf(
            task("b", cal = 2), task("a", cal = 2), task("c", cal = 1), task("done", cal = 1, done = true),
            task("dated", cal = 1, due = utcMidnight("2026-11-18"), dueAllDay = true),
        )
        assertEquals(listOf("c", "a", "b"), undatedTasks(list).map { it.title })
    }

    @Test fun aTimedTaskShowsItsClockTimeAndADateOnlyOneNone() {
        assertEquals("14:05", taskTimeText(task("t", due = ms("2026-11-18", "14:05")), athens))
        assertNull(taskTimeText(task("d", due = utcMidnight("2026-11-18"), dueAllDay = true), athens))
        assertNull(taskTimeText(task("u"), athens))
    }

    @Test fun overdueMeansAnOpenDeadlineThatHasPassed() {
        val now = ms("2026-11-18", "12:00")
        val today = LocalDate.of(2026, 11, 18)
        assertTrue(isOverdue(task("t", due = ms("2026-11-18", "11:00")), now, today))
        assertFalse(isOverdue(task("t", due = ms("2026-11-18", "13:00")), now, today))
        assertFalse(isOverdue(task("d", due = utcMidnight("2026-11-18"), dueAllDay = true), now, today))    // due today: not late yet
        assertTrue(isOverdue(task("d", due = utcMidnight("2026-11-17"), dueAllDay = true), now, today))
        assertFalse(isOverdue(task("s", start = utcMidnight("2026-11-01"), startAllDay = true), now, today))  // only a deadline can be missed
        assertFalse(isOverdue(task("f", due = utcMidnight("2026-11-01"), dueAllDay = true, done = true), now, today))
    }
}
