package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tasks this app may tick: only a plain one (docs: tasks slice 2). Generated texts only. */
class TaskEditabilityTest {
    private fun todo(vararg lines: String, extra: String = "") = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:t@invalid\r\nSUMMARY:Generated\r\n" +
        lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\n" + extra + "END:VCALENDAR\r\n"

    private fun readOnly(reason: TaskReadOnlyReason) = TaskEditability.ReadOnly(reason)

    @Test fun aPlainTaskInAWritableListIsEditable() {
        assertEquals(TaskEditability.Editable, TaskEditability.of(todo("DUE;VALUE=DATE:20261120"), listWritable = true))
        // an alarm, odd X- lines and a LF file do not matter
        assertEquals(TaskEditability.Editable, TaskEditability.of(todo("X-OTHER:1", "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT5M", "END:VALARM"), true))
        assertEquals(TaskEditability.Editable, TaskEditability.of(todo("STATUS:IN-PROCESS").replace("\r\n", "\n"), true))
        // a subtask itself is a plain task
        assertEquals(TaskEditability.Editable, TaskEditability.of(todo("RELATED-TO:parent@invalid"), true))
    }

    @Test fun aReadOnlyListComesFirst() {
        assertEquals(readOnly(TaskReadOnlyReason.ReadOnlyList), TaskEditability.of(todo("RRULE:FREQ=WEEKLY"), listWritable = false))
    }

    @Test fun repeatingTasksAreNotTickedHere() {
        for (l in listOf("RRULE:FREQ=WEEKLY", "RDATE;VALUE=DATE:20261201", "EXDATE;VALUE=DATE:20261127", "rrule:FREQ=DAILY")) {
            assertEquals(l, readOnly(TaskReadOnlyReason.Repeating), TaskEditability.of(todo("DTSTART;VALUE=DATE:20261120", l), true))
        }
        // a series with a finished copy (RECURRENCE-ID), as other apps store it
        val copy = "BEGIN:VTODO\r\nUID:t@invalid\r\nRECURRENCE-ID;VALUE=DATE:20261120\r\nSTATUS:COMPLETED\r\nEND:VTODO\r\n"
        assertEquals(readOnly(TaskReadOnlyReason.Repeating), TaskEditability.of(todo("RRULE:FREQ=WEEKLY", extra = copy), true))
        assertEquals(readOnly(TaskReadOnlyReason.Repeating), TaskEditability.of(todo("RECURRENCE-ID;VALUE=DATE:20261120"), true))
    }

    @Test fun aParentWithOpenSubtasksIsNotTickedHere() {
        assertEquals(readOnly(TaskReadOnlyReason.OpenSubtasks), TaskEditability.of(todo(), true, hasOpenSubtasks = true))
    }

    @Test fun tasksWithOtherPeopleAreNeverWritten() {
        assertEquals(readOnly(TaskReadOnlyReason.WithPeople), TaskEditability.of(todo("ORGANIZER:mailto:a@example.org"), true))
        assertEquals(readOnly(TaskReadOnlyReason.WithPeople), TaskEditability.of(todo("ATTENDEE:mailto:b@example.org"), true))
    }

    @Test fun objectsTheSurgeryCannotHandleAreLeftAlone() {
        val second = "BEGIN:VTODO\r\nUID:other@invalid\r\nSUMMARY:Second\r\nEND:VTODO\r\n"
        assertEquals(readOnly(TaskReadOnlyReason.Unusual), TaskEditability.of(todo(extra = second), true))
        val event = "BEGIN:VEVENT\r\nUID:e@invalid\r\nDTSTART:20261120T100000Z\r\nEND:VEVENT\r\n"
        assertEquals(readOnly(TaskReadOnlyReason.Unusual), TaskEditability.of(todo(extra = event), true))
        assertEquals(readOnly(TaskReadOnlyReason.NotATask), TaskEditability.of("BEGIN:VCALENDAR\r\n" + event + "END:VCALENDAR\r\n", true))
        assertEquals(readOnly(TaskReadOnlyReason.NotATask), TaskEditability.of("not a calendar at all", true))
        // a broken file: the VTODO never ends
        assertEquals(readOnly(TaskReadOnlyReason.Unusual), TaskEditability.of("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:x\r\nEND:VCALENDAR\r\n", true))
    }

    @Test fun finishedIsToldApart() {
        assertTrue(TaskEditability.isFinished(todo("STATUS:COMPLETED")))
        assertTrue(TaskEditability.isFinished(todo("STATUS:CANCELLED")))
        assertTrue(TaskEditability.isFinished(todo("COMPLETED:20261101T000000Z")))
        assertFalse(TaskEditability.isFinished(todo("PERCENT-COMPLETE:100")))          // 100 percent alone stays open, as in BookahTasks
        assertFalse(TaskEditability.isFinished(todo("STATUS:NEEDS-ACTION")))
        assertTrue(TaskEditability.isFinished(TaskDone.complete(todo(), java.time.Instant.EPOCH)))
        assertFalse(TaskEditability.isFinished(TaskDone.reopen(TaskDone.complete(todo(), java.time.Instant.EPOCH), java.time.Instant.EPOCH, TaskDone.BeforeTick.Open)))
    }

    @Test fun aFileThatMixesCrlfAndBareLineFeedsIsLeftAlone() {
        // the review's case: with CRLF as the file's style, "STATUS:NEEDS-ACTION\nX-OTHER:kept" would be read as ONE line and the X- line lost
        val mixed = todo("STATUS:NEEDS-ACTION\nX-OTHER:kept")
        assertEquals(readOnly(TaskReadOnlyReason.Unusual), TaskEditability.of(mixed, true))
        val mixedElsewhere = todo("DUE;VALUE=DATE:20261120").replace("VERSION:2.0\r\n", "VERSION:2.0\n")
        assertEquals(readOnly(TaskReadOnlyReason.Unusual), TaskEditability.of(mixedElsewhere, true))
        // all LF or all CRLF is fine
        assertEquals(TaskEditability.Editable, TaskEditability.of(todo("STATUS:NEEDS-ACTION", "X-OTHER:kept").replace("\r\n", "\n"), true))
    }

    @Test fun theTrialEditMayOnlyChangeTheTickLines() {
        val ics = todo("STATUS:NEEDS-ACTION", "X-OTHER:kept")
        val done = TaskDone.complete(ics, java.time.Instant.EPOCH)
        assertTrue(TaskEditability.onlyTickLinesDiffer(ics, done))
        // a lost line, a changed line or two lines run together are caught
        assertFalse(TaskEditability.onlyTickLinesDiffer(ics, done.replace("X-OTHER:kept\r\n", "")))
        assertFalse(TaskEditability.onlyTickLinesDiffer(ics, done.replace("SUMMARY:Generated", "SUMMARY:Other")))
        assertFalse(TaskEditability.onlyTickLinesDiffer(ics, done.replace("STATUS:COMPLETED\r\nX-OTHER:kept", "STATUS:COMPLETED X-OTHER:kept")))
    }

    @Test fun theOpenParentsAreTheParentsOfOpenSubtasks() {
        fun t(uid: String, parent: String?, done: Boolean) = TaskItem(1, uid, "/l/$uid.ics", uid, null, null, false, null, false, done, 0, parent, null)
        val tasks = listOf(t("p1", null, false), t("c1", "p1", false), t("p2", null, false), t("c2", "p2", true))
        assertEquals(setOf("p1"), openParentUids(tasks))
    }
}
