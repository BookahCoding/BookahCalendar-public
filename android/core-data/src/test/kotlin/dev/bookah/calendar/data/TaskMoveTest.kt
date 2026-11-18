package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Giving an undated task a day (and taking it back): the line surgery and who may be moved. Generated tasks only. */
class TaskMoveTest {
    private val now = Instant.parse("2026-11-18T10:15:30Z")
    private val day = LocalDate.of(2026, 11, 20)

    private fun todo(vararg lines: String, nl: String = "\r\n") = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//generated//EN", "BEGIN:VTODO",
        "UID:m@invalid", "DTSTAMP:20260101T000000Z", "SUMMARY:Generated undated task", *lines, "END:VTODO", "END:VCALENDAR").joinToString(nl) + nl

    @Test fun aDayIsWrittenAsADateOnlyStartAsBookahTasksDoesWithNoDeadline() {
        val out = TaskMove.setDay(todo("SEQUENCE:2"), day, now)
        assertTrue("DTSTART;VALUE=DATE:20261120\r\n" in out)
        assertFalse("DUE" in out)
        assertTrue("DTSTAMP:20261118T101530Z\r\n" in out && "LAST-MODIFIED:20261118T101530Z\r\n" in out && "SEQUENCE:3\r\n" in out)
        // the same reading the calendar uses: placed on that date, as a date
        val t = TaskReader.parse(1, "/a.ics", out, ZoneOffset.UTC).single()
        assertTrue(t.startAllDay)
        assertEquals(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), t.start)
        assertNull(t.due)
    }

    @Test fun everyOtherLineIsKeptByteForByteAndTheNewLineGoesBeforeTheAlarm() {
        val before = todo("X-GENERATED-APP:kept", "DESCRIPTION:Generated note long enough to be folded by the writer over more than one physical\r\n  line of the file.",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:r", "TRIGGER:-PT5M", "END:VALARM")
        val out = TaskMove.setDay(before, day, now)
        assertTrue("X-GENERATED-APP:kept\r\n" in out)
        assertTrue("physical\r\n  line of the file." in out)
        assertTrue(out.indexOf("DTSTART;VALUE=DATE") < out.indexOf("BEGIN:VALARM"))
        val back = TaskMove.clearDay(out, now.plusSeconds(5))
        val rest = { s: String -> IcsEdit.logicalLines(s).filter { IcsEdit.name(it) !in setOf("DTSTAMP", "LAST-MODIFIED", "SEQUENCE") } }
        assertEquals(rest(before), rest(back))          // undo: exactly "no dates" again, nothing else changed
    }

    @Test fun undoTakesTheStartAwayAgain() {
        val back = TaskMove.clearDay(TaskMove.setDay(todo(), day, now), now)
        assertFalse("DTSTART" in back)
        assertFalse("DUE" in back)
        assertEquals("2", TaskPatch.readProperty(back, "SEQUENCE"))
        val t = TaskReader.parse(1, "/a.ics", back, ZoneOffset.UTC).single()
        assertNull(t.start)
        assertNull(t.due)
    }

    @Test fun aFileWithBareNewlinesKeepsThem() {
        val out = TaskMove.setDay(todo(nl = "\n"), day, now)
        assertFalse("\r" in out)
        assertTrue("DTSTART;VALUE=DATE:20261120\n" in out)
    }

    @Test fun onlyAPlainUndatedTaskInAWritableListMayBeMoved() {
        assertEquals(TaskEditability.Editable, TaskMove.editability(todo(), listWritable = true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList), TaskMove.editability(todo(), listWritable = false))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Repeating), TaskMove.editability(todo("RRULE:FREQ=WEEKLY"), true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.WithPeople), TaskMove.editability(todo("ATTENDEE:mailto:a@example.org"), true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Finished), TaskMove.editability(todo("STATUS:COMPLETED"), true))
        val two = todo().replace("END:VCALENDAR", "BEGIN:VTODO\r\nUID:n@invalid\r\nEND:VTODO\r\nEND:VCALENDAR")
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Unusual), TaskMove.editability(two, true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Unusual), TaskMove.editability(todo("DURATION:PT1H"), true))
    }

    @Test fun aTaskWithAnyDateLineIsNotUndatedEvenWhenTheDateCannotBeRead() {
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.HasDates), TaskMove.editability(todo("DUE;VALUE=DATE:20261121"), true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.HasDates), TaskMove.editability(todo("DTSTART:20261121T090000Z"), true))
        // a broken start: the calendar shows the task without a date, but the line is there and must never be overwritten
        val broken = todo("DTSTART:not-a-date")
        assertNull(TaskReader.parse(1, "/a.ics", broken, ZoneOffset.UTC).single().start)
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.HasDates), TaskMove.editability(broken, true))
    }

    @Test fun aTimeZoneBlocksStartIsNotTheTasksStart() {
        val withZone = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTIMEZONE\r\nTZID:Europe/Athens\r\nBEGIN:STANDARD\r\nDTSTART:19701025T040000\r\nTZOFFSETFROM:+0300\r\n" +
            "TZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\nBEGIN:VTODO\r\nUID:z@invalid\r\nSUMMARY:Generated\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        assertEquals(TaskEditability.Editable, TaskMove.editability(withZone, true))
        val out = TaskMove.setDay(withZone, day, now)
        assertTrue("DTSTART:19701025T040000\r\n" in out)          // the zone's own line untouched
        assertTrue("DTSTART;VALUE=DATE:20261120\r\n" in out)
    }

    @Test fun aTaskParkedForSomedayIsLeftToBookahTasks() {
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Parked), TaskMove.editability(todo("CATEGORIES:someday"), true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Parked), TaskMove.editability(todo("CATEGORIES:Work,someday"), true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Parked), TaskMove.editability(todo("CATEGORIES:SOMEDAY"), true))
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Parked), TaskMove.editability(todo("CATEGORIES:Work", "CATEGORIES:Someday"), true))
        // an escaped comma is part of one tag: "Some,day" is not the Someday tag, nor is a tag that only contains the word
        assertEquals(TaskEditability.Editable, TaskMove.editability(todo("CATEGORIES:Some\\,day"), true))
        assertEquals(TaskEditability.Editable, TaskMove.editability(todo("CATEGORIES:Work,somedays"), true))
        assertFalse(TaskMove.isParked(todo("CATEGORIES:Work")))
    }

    @Test fun anOpenSubtaskDoesNotStopTheParentGettingADay() {
        // the writer is told about subtasks only for a tick; a move never asks
        assertEquals(TaskEditability.Editable, TaskMove.editability(todo(), listWritable = true))
    }

    @Test fun undoIsOnlyForTheStartThisAppWrote() {
        val moved = TaskMove.setDay(todo(), day, now)
        assertTrue(TaskMove.isOurMove(moved, day))
        assertFalse(TaskMove.isOurMove(moved, day.plusDays(1)))
        assertFalse(TaskMove.isOurMove(TaskPatch.setProperty(moved, "DUE", "20261122", params = "VALUE=DATE"), day))
        assertFalse(TaskMove.isOurMove(todo(), day))
    }

    @Test fun setPropertyWritesParametersAndReplacesOldOnes() {
        val out = TaskPatch.setProperty(todo("DTSTART;TZID=Europe/Athens:20261120T090000"), "DTSTART", "20261121", params = "VALUE=DATE")
        assertTrue("DTSTART;VALUE=DATE:20261121\r\n" in out)
        assertFalse("TZID" in out)
    }
}
