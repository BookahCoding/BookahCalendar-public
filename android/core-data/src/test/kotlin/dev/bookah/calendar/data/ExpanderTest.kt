package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Generated events only: no real data in this file. */
class ExpanderTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val expander = Expander()

    private fun ics(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" + events.joinToString("") { it.trim().replace("\n", "\r\n") + "\r\n" } + "END:VCALENDAR\r\n"
    private fun src(text: String, id: Long = 1) = ObjectSource(id, "/cal/x.ics", text, "k${text.hashCode()}", readOnly = false)
    private fun utc(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun local(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).atZone(athens).toInstant().toEpochMilli()
    private fun expand(text: String, from: Long, to: Long) = expander.expand(listOf(src(text)), from, to).sortedBy { it.start }

    @Test fun weeklyEventKeepsLocalTimeAcrossTheDaylightSavingChange() {
        // Mondays 10:00 Athens time; clocks go back on 25 Oct 2026
        val text = ics("""
BEGIN:VEVENT
UID:w1
DTSTART;TZID=Europe/Athens:20261019T100000
DTEND;TZID=Europe/Athens:20261019T110000
RRULE:FREQ=WEEKLY;COUNT=3
SUMMARY:Weekly test
END:VEVENT""")
        val r = expand(text, utc(2026, 10, 1), utc(2026, 12, 1))
        assertEquals(listOf(local(2026, 10, 19, 10), local(2026, 10, 26, 10), local(2026, 11, 2, 10)), r.map { it.start })
        assertEquals(3_600_000L, r[0].end - r[0].start)
        assertEquals("Weekly test", r[0].title)
        assertEquals("Europe/Athens", r[0].zoneId)
    }

    @Test fun allDayMultiDayEventSpansItsDays() {
        val text = ics("""
BEGIN:VEVENT
UID:a1
DTSTART;VALUE=DATE:20261110
DTEND;VALUE=DATE:20261113
SUMMARY:Trip
END:VEVENT""")
        val r = expand(text, utc(2026, 11, 12), utc(2026, 11, 13))
        assertEquals(1, r.size)
        assertTrue(r[0].allDay)
        assertEquals(utc(2026, 11, 10), r[0].start)
        assertEquals(utc(2026, 11, 13), r[0].end)                    // end is exclusive: the last day is the 12th
        assertEquals(0, expand(text, utc(2026, 11, 13), utc(2026, 11, 14)).size)
    }

    @Test fun yearlyAllDayRepeatsEveryYear() {
        val text = ics("""
BEGIN:VEVENT
UID:b1
DTSTART;VALUE=DATE:19900305
RRULE:FREQ=YEARLY
SUMMARY:Birthday
END:VEVENT""")
        val r = expand(text, utc(2026, 1, 1), utc(2028, 1, 1))
        assertEquals(listOf(utc(2026, 3, 5), utc(2027, 3, 5)), r.map { it.start })
        assertTrue(r.all { it.allDay && it.end - it.start == Expander.DAY_MS })
    }

    @Test fun excludedDatesAndEditedInstances() {
        val text = ics(
            """
BEGIN:VEVENT
UID:c1
DTSTART;TZID=Europe/Athens:20261102T180000
DTEND;TZID=Europe/Athens:20261102T190000
RRULE:FREQ=WEEKLY;COUNT=5
EXDATE;TZID=Europe/Athens:20261109T180000
SUMMARY:Class
END:VEVENT""",
            """
BEGIN:VEVENT
UID:c1
RECURRENCE-ID;TZID=Europe/Athens:20261116T180000
DTSTART;TZID=Europe/Athens:20261117T200000
DTEND;TZID=Europe/Athens:20261117T210000
SUMMARY:Class (moved)
END:VEVENT""",
        )
        val r = expand(text, utc(2026, 11, 1), utc(2026, 12, 31))
        assertEquals(
            listOf(local(2026, 11, 2, 18), local(2026, 11, 17, 20), local(2026, 11, 23, 18), local(2026, 11, 30, 18)),
            r.map { it.start },
        )
        assertEquals("Class (moved)", r[1].title)
    }

    @Test fun untilAndCountStopTheSeries() {
        val until = ics("""
BEGIN:VEVENT
UID:d1
DTSTART;VALUE=DATE:20261101
RRULE:FREQ=DAILY;UNTIL=20261104
SUMMARY:Short run
END:VEVENT""")
        assertEquals(4, expand(until, utc(2026, 10, 1), utc(2027, 1, 1)).size)
    }

    @Test fun eventsOutsideTheRangeAreLeftOut() {
        val text = ics("""
BEGIN:VEVENT
UID:e1
DTSTART:20260105T100000Z
DTEND:20260105T110000Z
SUMMARY:January
END:VEVENT""")
        assertEquals(0, expand(text, utc(2026, 2, 1), utc(2026, 3, 1)).size)
        assertEquals(1, expand(text, utc(2026, 1, 1), utc(2026, 2, 1)).size)
    }

    @Test fun cancelledEventsAreSkipped() {
        val text = ics("""
BEGIN:VEVENT
UID:f1
DTSTART:20261105T100000Z
DTEND:20261105T110000Z
STATUS:CANCELLED
SUMMARY:Called off
END:VEVENT""")
        assertEquals(0, expand(text, utc(2026, 11, 1), utc(2026, 12, 1)).size)
    }

    @Test fun readsPlaceNotesAndReminders() {
        val text = ics("""
BEGIN:VEVENT
UID:g1
DTSTART:20261105T100000Z
DTEND:20261105T110000Z
SUMMARY:Dentist
LOCATION:Main Street 1
DESCRIPTION:Bring the form
BEGIN:VALARM
ACTION:DISPLAY
TRIGGER:-PT30M
END:VALARM
BEGIN:VALARM
ACTION:DISPLAY
TRIGGER:-P1D
END:VALARM
END:VEVENT""")
        val e = expand(text, utc(2026, 11, 1), utc(2026, 12, 1)).single()
        assertEquals("Main Street 1", e.location); assertEquals("Bring the form", e.description)
        assertEquals(listOf(1440, 30), e.alarmMinutes)
    }

    @Test fun describesRepeats() {
        val text = ics("""
BEGIN:VEVENT
UID:h1
DTSTART;TZID=Europe/Athens:20261103T100000
DTEND;TZID=Europe/Athens:20261103T110000
RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH;COUNT=6
SUMMARY:Pattern
END:VEVENT""")
        val rep = expand(text, utc(2026, 11, 1), utc(2026, 12, 1)).first().repeat!!
        assertEquals("WEEKLY", rep.frequency); assertEquals(2, rep.interval); assertEquals(listOf("TU", "TH"), rep.byDay); assertEquals(6, rep.count)
    }

    @Test fun brokenObjectsDoNotBreakTheOthers() {
        val good = ics("""
BEGIN:VEVENT
UID:i1
DTSTART:20261105T100000Z
DTEND:20261105T110000Z
SUMMARY:Fine
END:VEVENT""")
        val r = expander.expand(listOf(src("this is not a calendar", 1), src(good, 2)), utc(2026, 11, 1), utc(2026, 12, 1))
        assertEquals(listOf("Fine"), r.map { it.title })
    }

    @Test fun splitsAFeedIntoOneObjectPerUid() {
        val feed = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:one\r\nDTSTART:20261105T100000Z\r\nDTEND:20261105T110000Z\r\nRRULE:FREQ=DAILY;COUNT=2\r\nSUMMARY:A\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:one\r\nRECURRENCE-ID:20261106T100000Z\r\nDTSTART:20261106T120000Z\r\nDTEND:20261106T130000Z\r\nSUMMARY:A moved\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:two\r\nDTSTART:20261107T100000Z\r\nDTEND:20261107T110000Z\r\nSUMMARY:B\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val parts = Expander.splitFeed(feed)
        assertEquals(listOf("one", "two"), parts.map { it.first })
        val r = expander.expand(parts.map { ObjectSource(9, "g:${it.first}", it.second, "g${it.first}", true) }, utc(2026, 11, 1), utc(2026, 12, 1))
        assertEquals(listOf("A", "A moved", "B"), r.sortedBy { it.start }.map { it.title })
        assertTrue(r.all { it.readOnly })
    }

    @Test fun readsTheUidWithoutParsing() {
        assertEquals("abc-1", Expander.uidOf("BEGIN:VEVENT\r\nUID:abc-1\r\nEND:VEVENT"))
    }
}
