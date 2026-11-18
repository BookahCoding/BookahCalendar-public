package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Ticking a task by text surgery: the same lines as BookahTasks writes, every other line kept byte for byte. Generated tasks only. */
class TaskDoneTest {
    private val now = Instant.parse("2026-11-18T10:15:30Z")
    private val later = Instant.parse("2026-11-18T10:16:00Z")

    private val folded = "DESCRIPTION:Generated notes that are long enough to be folded over more than one\r\n  physical line by the writer.\r\n"
    private val plain = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VTODO\r\nUID:t1@invalid\r\nDTSTAMP:20260101T000000Z\r\n" +
        "SUMMARY:Generated task\r\nSTATUS:NEEDS-ACTION\r\nX-GENERATED-OTHER-APP;X-PARAM=1:kept as it is\r\n" + folded +
        "DUE;VALUE=DATE:20261120\r\nSEQUENCE:2\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

    private fun lines(ics: String, name: String) = IcsEdit.logicalLines(ics).filter { IcsEdit.name(it) == name }

    /** The text without the lines a tick or an undo may change (each line kept with its own bytes, folds included). */
    private fun untouched(ics: String) = IcsEdit.logicalLines(ics).filter { IcsEdit.name(it) !in setOf("STATUS", "COMPLETED", "PERCENT-COMPLETE", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE") }

    @Test fun completeWritesStatusCompletedTimeAndPercentLikeBookahTasks() {
        val done = TaskDone.complete(plain, now)
        assertEquals(listOf("STATUS:COMPLETED"), lines(done, "STATUS"))
        assertEquals(listOf("COMPLETED:20261118T101530Z"), lines(done, "COMPLETED"))
        assertEquals(listOf("PERCENT-COMPLETE:100"), lines(done, "PERCENT-COMPLETE"))
        assertEquals(listOf("DTSTAMP:20261118T101530Z"), lines(done, "DTSTAMP"))
        assertEquals(listOf("LAST-MODIFIED:20261118T101530Z"), lines(done, "LAST-MODIFIED"))
        assertEquals(listOf("SEQUENCE:3"), lines(done, "SEQUENCE"))
        assertEquals(untouched(plain), untouched(done))
    }

    @Test fun theStatusLineIsReplacedInItsPlace() {
        val done = TaskDone.complete(plain, now)
        assertTrue("SUMMARY:Generated task\r\nSTATUS:COMPLETED\r\nX-GENERATED-OTHER-APP" in done)
    }

    @Test fun unknownPropertiesCrlfAndFoldingAreKeptByteForByte() {
        val done = TaskDone.complete(plain, now)
        assertTrue("X-GENERATED-OTHER-APP;X-PARAM=1:kept as it is\r\n" in done)
        assertTrue(folded in done)
        assertFalse(done.replace("\r\n", "").contains("\n"))        // no bare LF crept in
        assertTrue(done.endsWith("END:VCALENDAR\r\n"))
    }

    @Test fun aFileWithBareLineFeedsKeepsThem() {
        val lf = plain.replace("\r\n", "\n")
        val done = TaskDone.complete(lf, now)
        assertFalse("\r" in done)
        assertTrue("X-GENERATED-OTHER-APP;X-PARAM=1:kept as it is\n" in done)
        assertTrue(folded.replace("\r\n", "\n") in done)
    }

    @Test fun reopenIsTheInverse() {
        val back = TaskDone.reopen(TaskDone.complete(plain, now), later, TaskDone.before(plain))
        assertEquals(listOf("STATUS:NEEDS-ACTION"), lines(back, "STATUS"))
        assertTrue(lines(back, "COMPLETED").isEmpty())
        assertTrue(lines(back, "PERCENT-COMPLETE").isEmpty())
        assertEquals(listOf("LAST-MODIFIED:20261118T101600Z"), lines(back, "LAST-MODIFIED"))
        assertEquals(listOf("SEQUENCE:4"), lines(back, "SEQUENCE"))
        assertEquals(untouched(plain), untouched(back))
        // apart from the change times, it is the original again
        assertEquals(plain.replace("DTSTAMP:20260101T000000Z", "DTSTAMP:20261118T101600Z").replace("SEQUENCE:2", "SEQUENCE:4"), back.replace(Regex("LAST-MODIFIED:[^\r]*\r\n"), ""))
    }

    @Test fun completingTwiceStillLeavesOneLineOfEach() {
        val twice = TaskDone.complete(TaskDone.complete(plain, now), later)
        assertEquals(1, lines(twice, "STATUS").size)
        assertEquals(listOf("COMPLETED:20261118T101600Z"), lines(twice, "COMPLETED"))
        assertEquals(1, lines(twice, "PERCENT-COMPLETE").size)
        assertEquals(1, lines(twice, "LAST-MODIFIED").size)
        val reopenedTwice = TaskDone.reopen(TaskDone.reopen(twice, later, TaskDone.before(plain)), later, TaskDone.before(plain))
        assertEquals(listOf("STATUS:NEEDS-ACTION"), lines(reopenedTwice, "STATUS"))
        assertTrue(lines(reopenedTwice, "COMPLETED").isEmpty())
    }

    @Test fun aTaskWithoutStatusGetsTheLinesBeforeItsAlarmAndTheAlarmSurvives() {
        val alarm = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:r\r\nTRIGGER:-PT2M\r\nSTATUS:X-NOT-THE-TASKS\r\nEND:VALARM\r\n"
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:t2@invalid\r\nSUMMARY:No status\r\n" + alarm + "END:VTODO\r\nEND:VCALENDAR\r\n"
        val done = TaskDone.complete(ics, now)
        assertTrue(alarm in done)                                        // the alarm block is untouched, its own lines included
        val head = done.substringBefore("BEGIN:VALARM")
        assertTrue("STATUS:COMPLETED\r\n" in head && "COMPLETED:20261118T101530Z\r\n" in head && "PERCENT-COMPLETE:100\r\n" in head)
        assertTrue("SEQUENCE:1\r\n" in head)                             // no SEQUENCE before: 0 + 1, as BookahTasks
        val back = TaskDone.reopen(done, later, TaskDone.before(ics))
        assertTrue(alarm in back)
        // a true undo: it had no STATUS of its own (the alarm's does not count), so it gets none back
        assertTrue(lines(back.substringBefore("BEGIN:VALARM"), "STATUS").isEmpty())
        assertTrue(lines(back, "COMPLETED").isEmpty() && lines(back, "PERCENT-COMPLETE").isEmpty())
    }

    @Test fun undoBringsBackTheStatusAndPercentFromBeforeTheTick() {
        val ics = plain.replace("STATUS:NEEDS-ACTION", "STATUS:IN-PROCESS\r\nPERCENT-COMPLETE:40")
        val before = TaskDone.before(ics)
        assertEquals(TaskDone.BeforeTick("IN-PROCESS", "40"), before)
        val done = TaskDone.complete(ics, now)
        assertEquals(listOf("PERCENT-COMPLETE:100"), lines(done, "PERCENT-COMPLETE"))
        val back = TaskDone.reopen(done, later, before)
        assertEquals(listOf("PERCENT-COMPLETE:40"), lines(back, "PERCENT-COMPLETE"))
        assertEquals(listOf("STATUS:IN-PROCESS"), lines(back, "STATUS"))
        assertTrue(lines(back, "COMPLETED").isEmpty())
        assertEquals(untouched(ics), untouched(back))
    }

    @Test fun withoutAnythingRememberedUndoOpensItAsBookahTasksDoes() {
        val back = TaskDone.reopen(TaskDone.complete(plain.replace("STATUS:NEEDS-ACTION", "STATUS:IN-PROCESS"), now), later, TaskDone.BeforeTick.Open)
        assertEquals(listOf("STATUS:NEEDS-ACTION"), lines(back, "STATUS"))
        assertTrue(lines(back, "PERCENT-COMPLETE").isEmpty())
    }

    @Test fun theEventsAndOtherBlocksOfTheFileAreNotTouched() {
        val tz = "BEGIN:VTIMEZONE\r\nTZID:Europe/Athens\r\nBEGIN:STANDARD\r\nDTSTART:19701025T040000\r\nTZOFFSETFROM:+0300\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
        val ics = plain.replace("PRODID:-//generated//EN\r\n", "PRODID:-//generated//EN\r\n$tz")
        val done = TaskDone.complete(ics, now)
        assertTrue(tz in done)
    }

    @Test fun aLongValueIsFoldedAt75Bytes() {
        assertEquals("A".repeat(75) + "\r\n " + "B", TaskPatch.fold("A".repeat(75) + "B"))
        assertNull(TaskPatch.readProperty(plain, "COMPLETED"))
        assertEquals("Generated task", TaskPatch.readProperty(plain, "SUMMARY"))
    }
}
