package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Reading Nextcloud tasks (VTODO) for display only. All items are generated here. */
class TaskReaderTest {
    private val athens = ZoneId.of("Europe/Athens")

    private fun ics(vararg lines: String, extra: String = "") =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VTODO\r\nUID:t1@invalid\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Generated task\r\n" +
            lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\n" + extra + "END:VCALENDAR\r\n"

    private fun one(text: String) = TaskReader.parse(7, "/c/tasks/t1.ics", text, athens).single()
    private fun utcMidnight(date: String) = LocalDate.parse(date).toEpochDay() * 86_400_000L
    private fun athensMs(date: String, time: String) = LocalDateTime.parse("${date}T$time").atZone(athens).toInstant().toEpochMilli()

    @Test fun aDateOnlyDeadlineIsAnAllDayDate() {
        val t = one(ics("DUE;VALUE=DATE:20261118"))
        assertEquals(utcMidnight("2026-11-18"), t.due)
        assertTrue(t.dueAllDay)
        assertNull(t.start)
        assertEquals(t.due, t.placedAt); assertTrue(t.placedAllDay)
    }

    @Test fun aTimedDeadlineInUtcIsThatInstant() {
        val t = one(ics("DUE:20261118T120000Z"))
        assertEquals(athensMs("2026-11-18", "14:00"), t.due)
        assertFalse(t.dueAllDay)
    }

    @Test fun aDeadlineWithAZoneNameIsReadInThatZoneEvenWithoutItsTimezoneBlock() {
        val t = one(ics("DUE;TZID=Europe/Athens:20261118T090000"))
        assertEquals(athensMs("2026-11-18", "09:00"), t.due)
    }

    @Test fun aFloatingTimeIsTheSameClockTimeOnThePhone() {
        val t = one(ics("DUE:20261118T163000"))
        assertEquals(athensMs("2026-11-18", "16:30"), t.due)
        assertFalse(t.dueAllDay)
    }

    @Test fun aStartAtMidnightBesideATimedDeadlineIsReallyADateOnlyStart() {
        // BookahTasks stores a start without a time as 00:00 local when the deadline has a time (the server wants both of one kind)
        val t = one(ics("DTSTART:20261117T220000Z", "DUE:20261119T120000Z"))       // 18 Nov 00:00 and 19 Nov 14:00 in Athens
        assertEquals(utcMidnight("2026-11-18"), t.start); assertTrue(t.startAllDay)
        assertEquals(athensMs("2026-11-19", "14:00"), t.due); assertFalse(t.dueAllDay)
    }

    @Test fun aDeadlineAt2359BesideATimedStartIsReallyADateOnlyDeadline() {
        val t = one(ics("DTSTART:20261118T070000Z", "DUE:20261119T215900Z"))       // 18 Nov 09:00 and 19 Nov 23:59 in Athens
        assertEquals(athensMs("2026-11-18", "09:00"), t.start); assertFalse(t.startAllDay)
        assertEquals(utcMidnight("2026-11-19"), t.due); assertTrue(t.dueAllDay)
    }

    @Test fun a2359DeadlineWithoutAStartKeepsItsTime() {
        val t = one(ics("DUE:20261119T215900Z"))
        assertEquals(athensMs("2026-11-19", "23:59"), t.due); assertFalse(t.dueAllDay)
    }

    @Test fun withoutADeadlineTheStartPlacesTheTask() {
        val t = one(ics("DTSTART;VALUE=DATE:20261120"))
        assertNull(t.due)
        assertEquals(utcMidnight("2026-11-20"), t.placedAt); assertTrue(t.placedAllDay)
    }

    @Test fun withoutAnyDateTheTaskIsUndated() {
        val t = one(ics())
        assertNull(t.placedAt)
    }

    @Test fun finishedMeansCompletedOrCancelledLikeBookahTasks() {
        assertTrue(one(ics("STATUS:COMPLETED")).done)
        assertTrue(one(ics("STATUS:CANCELLED")).done)
        assertTrue(one(ics("COMPLETED:20261110T100000Z")).done)
        assertFalse(one(ics("STATUS:NEEDS-ACTION")).done)
        assertFalse(one(ics("STATUS:IN-PROCESS", "PERCENT-COMPLETE:50")).done)
        assertFalse(one(ics()).done)
    }

    @Test fun aHundredPercentTaskThatIsNotCompletedStaysOpen() {
        // BookahTasks (Task.kt) judges by STATUS and COMPLETED only; both apps must agree on what is open
        assertFalse(one(ics("PERCENT-COMPLETE:100")).done)
        assertFalse(one(ics("STATUS:NEEDS-ACTION", "PERCENT-COMPLETE:100")).done)
    }

    @Test fun twoTasksWithTheSameUidInOneItemAreShownOnce() {
        // a broken item with two VTODOs of one UID (no RECURRENCE-ID) would give two rows with the same key and crash a list
        val second = "BEGIN:VTODO\r\nUID:t1@invalid\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Second copy\r\nEND:VTODO\r\n"
        val list = TaskReader.parse(7, "/c/tasks/t1.ics", ics(extra = second), athens)
        assertEquals(listOf("Generated task"), list.map { it.title })
        val reader = TaskReader()
        val src = ObjectSource(7, "/c/tasks/t1.ics", ics(extra = second), "k", readOnly = true)
        assertEquals(1, reader.read(listOf(src, src.copy(cacheKey = "k2")), athens).size)
    }

    @Test fun oneBadDateDoesNotLoseTheTask() {
        val t = one(ics("DTSTART:20261318T090000Z", "DUE;VALUE=DATE:20261118"))
        assertNull(t.start)
        assertEquals(utcMidnight("2026-11-18"), t.due)
        assertTrue(t.dueAllDay)
    }

    @Test fun theFieldsTheTaskPageShows() {
        val t = one(ics("DESCRIPTION:Line one\\nLine two", "PRIORITY:1", "RELATED-TO:parent@invalid", "URL:https://example.org/page"))
        assertEquals("t1@invalid", t.uid)
        assertEquals("/c/tasks/t1.ics", t.objectHref)
        assertEquals(7L, t.calendarId)
        assertEquals("Generated task", t.title)
        assertEquals("Line one\nLine two", t.notes)
        assertEquals(1, t.priority)
        assertEquals("parent@invalid", t.parentUid)
        assertEquals("https://example.org/page", t.url)
    }

    @Test fun aChildRelationIsNotAParentAndNoPriorityIsZero() {
        val t = one(ics("RELATED-TO;RELTYPE=CHILD:kid@invalid"))
        assertNull(t.parentUid)
        assertEquals(0, t.priority)
        assertNull(t.notes); assertNull(t.url)
    }

    @Test fun greekTextIsKept() = assertEquals("Αγορά ψωμιού", one(ics().replace("SUMMARY:Generated task", "SUMMARY:Αγορά ψωμιού")).title)

    @Test fun completedCopiesOfARepeatingTaskAreIgnored() {
        val copy = "BEGIN:VTODO\r\nUID:t1@invalid\r\nDTSTAMP:20261101T000000Z\r\nRECURRENCE-ID:20261110T090000Z\r\nSUMMARY:Generated task\r\nSTATUS:COMPLETED\r\nEND:VTODO\r\n"
        val list = TaskReader.parse(7, "/c/tasks/t1.ics", ics("DUE:20261117T090000Z", "RRULE:FREQ=WEEKLY", extra = copy), athens)
        assertEquals(1, list.size)
        assertFalse(list.single().done)
    }

    @Test fun eventsAndBrokenTextGiveNoTasks() {
        val event = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:e@invalid\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261118T090000Z\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertTrue(TaskReader.parse(1, "/c/e.ics", event, athens).isEmpty())
        assertTrue(TaskReader.parse(1, "/c/broken.ics", "this is not a calendar", athens).isEmpty())
    }

    @Test fun theReaderKeepsEachCalendarsIdAndReadsAChangedItemAgain() {
        val reader = TaskReader()
        val a = ObjectSource(3, "/c/a/1.ics", ics(), "/c/a/1.ics|e1", readOnly = true)
        val b = ObjectSource(4, "/c/b/2.ics", ics("STATUS:COMPLETED"), "/c/b/2.ics|e1", readOnly = true)
        val first = reader.read(listOf(a, b), athens)
        assertEquals(listOf(3L, 4L), first.map { it.calendarId })
        // same key: the cached reading is used; a new ETag (new key) is read again
        assertEquals(first, reader.read(listOf(a, b), athens))
        val changed = b.copy(ics = ics(), cacheKey = "/c/b/2.ics|e2")
        assertFalse(reader.read(listOf(a, changed), athens).last().done)
    }
}
