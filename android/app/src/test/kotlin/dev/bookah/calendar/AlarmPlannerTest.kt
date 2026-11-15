package dev.bookah.calendar

import dev.bookah.calendar.data.Instance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class AlarmPlannerTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val now = LocalDate.of(2026, 11, 15).atTime(12, 0).atZone(athens).toInstant().toEpochMilli()
    private val hour = 3_600_000L; private val day = 86_400_000L

    private fun ev(title: String, start: Long, alarms: List<Int>, allDay: Boolean = false, original: Long = start, href: String = "h-$title") =
        Instance(1, title, href, title, "Room", null, start, start + hour, allDay, null, null, alarms, false, original)

    @Test fun aReminderInTheFutureBecomesAnAlarmThatManyMinutesBeforeTheStart() {
        val r = AlarmPlanner.plan(listOf(ev("a", now + 3 * hour, listOf(15, 60))), now, athens)
        assertEquals(listOf(now + 2 * hour, now + 3 * hour - 15 * 60_000L), r.map { it.triggerAtMs })
        assertEquals("Room", r[0].location)
    }

    @Test fun reminderTimesThatAlreadyPassedAreLeftOut() {
        val r = AlarmPlanner.plan(listOf(ev("soon", now + 10 * 60_000L, listOf(15, 5))), now, athens)     // the 15 minute one is already past
        assertEquals(listOf(now + 5 * 60_000L), r.map { it.triggerAtMs })
    }

    @Test fun eventsBeyondTheHorizonAreNotScheduledYet() {
        assertTrue(AlarmPlanner.plan(listOf(ev("far", now + 20 * day, listOf(10))), now, athens).isEmpty())
        assertEquals(1, AlarmPlanner.plan(listOf(ev("edge", now + 13 * day, listOf(10))), now, athens).size)
    }

    @Test fun eventsWithoutRemindersGiveNothing() = assertTrue(AlarmPlanner.plan(listOf(ev("none", now + hour, emptyList())), now, athens).isEmpty())

    @Test fun theNearestOnesAreKeptWhenThereAreTooMany() {
        val many = (1..80).map { ev("e$it", now + it * hour, listOf(0)) }
        val r = AlarmPlanner.plan(many, now, athens)
        assertEquals(AlarmPlanner.MAX_SCHEDULED, r.size)
        assertEquals(now + hour, r.first().triggerAtMs)
        assertEquals(r.map { it.triggerAtMs }, r.map { it.triggerAtMs }.sorted())
    }

    @Test fun theSameOccurrenceAndReminderHasTheSameKeyEveryTime() {
        val a = AlarmPlanner.plan(listOf(ev("s", now + 2 * hour, listOf(10), href = "/c/s.ics")), now, athens).single()
        val b = AlarmPlanner.plan(listOf(ev("s", now + 2 * hour, listOf(10), href = "/c/s.ics")), now + 60_000L, athens).single()
        assertEquals(a.key, b.key)
        // an edited and moved occurrence keeps its series slot in the key
        val moved = AlarmPlanner.plan(listOf(ev("s", now + 5 * hour, listOf(10), original = now + 2 * hour, href = "/c/s.ics")), now, athens).single()
        assertEquals(a.key, moved.key)
    }

    @Test fun anAllDayEventCountsFromMidnightInThePhonesZone() {
        val tomorrow = LocalDate.of(2026, 11, 16)
        val utcMidnight = tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val r = AlarmPlanner.plan(listOf(ev("birthday", utcMidnight, listOf(540), allDay = true)), now, athens).single()     // 9 hours before midnight = 15:00 the day before
        assertEquals(tomorrow.atStartOfDay(athens).toInstant().toEpochMilli() - 540 * 60_000L, r.triggerAtMs)
        assertTrue(r.allDay)
    }
}
