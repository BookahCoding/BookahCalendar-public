package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Giving an undated task a day, and the undo, against a fake server that enforces If-Match like Nextcloud. Generated tasks only. */
class TaskMoveWriterTest {
    private val server = FakeCalendarServer()
    private val store = MemoryCalendarStore()
    private var now = Instant.parse("2026-11-18T10:15:30Z")
    private val writer = TaskWriter(store, server, clock = { now })
    private val day = LocalDate.of(2026, 11, 20)

    private fun todo(uid: String, vararg lines: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Generated $uid\r\n" +
        lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n"

    private fun list(canWrite: Boolean = true, href: String = "/cal/tasks/", kind: ListKind = ListKind.Tasks): Long =
        store.replaceNextcloudCalendars(store.calendars().filter { it.source == Source.Nextcloud }.map { RemoteCalendar(it.href, it.name, it.colorHex, it.editable, it.kind) } +
            RemoteCalendar(href, "Tasks", "#3E8E41", canWrite, kind)).first { it.href == href }.id

    private fun seed(cal: Long, name: String, ics: String, etag: String = "e0"): String {
        val href = store.calendars().first { it.id == cal }.href + "$name.ics"
        server.objects[href] = RemoteObject(href, etag, ics)
        store.upsertObjects(cal, listOf(RemoteObject(href, etag, ics)))
        return href
    }

    @Test fun aDayIsWrittenWithIfMatchAndThePhoneKeepsTheServersEtag() = runTest {
        val href = seed(list(), "a", todo("a@invalid", "X-GENERATED-APP:kept"))
        val result = writer.setDay(href, day) as TaskWriteResult.Saved
        assertEquals(listOf("PUT If-Match"), server.log)
        val onServer = server.objects[href]!!
        assertEquals(onServer.etag, result.etag)
        assertEquals(onServer.etag, store.objectByHref(href)!!.etag)
        assertEquals(onServer.ics, store.objectByHref(href)!!.ics)
        assertTrue("DTSTART;VALUE=DATE:20261120\r\n" in onServer.ics && "X-GENERATED-APP:kept\r\n" in onServer.ics)
        assertFalse("DUE" in onServer.ics)
        assertTrue("SEQUENCE:1\r\n" in onServer.ics && "LAST-MODIFIED:20261118T101530Z\r\n" in onServer.ics)
    }

    @Test fun undoNamesTheEtagOfTheMoveAndGivesBackNoDates() = runTest {
        val before = todo("a@invalid")
        val href = seed(list(), "a", before)
        val moved = writer.setDay(href, day) as TaskWriteResult.Saved
        now = now.plusSeconds(4)
        assertTrue(writer.clearDay(href, moved.etag, day) is TaskWriteResult.Saved)
        assertEquals(listOf("PUT If-Match", "PUT If-Match"), server.log)
        val ics = server.objects[href]!!.ics
        assertFalse("DTSTART" in ics || "DUE" in ics)
        val t = TaskReader.parse(1, href, ics, java.time.ZoneOffset.UTC).single()
        assertNull(t.start)
        assertNull(t.due)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
    }

    @Test fun aChangeElsewhereIsAConflictNothingIsOverwrittenAndThePhoneShowsTheirVersion() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val theirs = todo("a@invalid").replace("SUMMARY:Generated a@invalid", "SUMMARY:Changed elsewhere")
        server.objects[href] = RemoteObject(href, "other", theirs)
        assertTrue(writer.setDay(href, day) is TaskWriteResult.Conflict)
        assertEquals(theirs, server.objects[href]!!.ics)
        assertEquals("other", store.objectByHref(href)!!.etag)
        assertEquals(theirs, store.objectByHref(href)!!.ics)
    }

    @Test fun aDateGivenElsewhereMeanwhileIsKept() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        // elsewhere it got a deadline: the server says 412, the phone takes it, and a second try is refused (it has a date now)
        server.objects[href] = RemoteObject(href, "other", todo("a@invalid", "DUE;VALUE=DATE:20261125"))
        assertTrue(writer.setDay(href, day) is TaskWriteResult.Conflict)
        server.log.clear()
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.HasDates), writer.setDay(href, day))
        assertTrue(server.log.isEmpty())
        assertTrue("DUE;VALUE=DATE:20261125" in server.objects[href]!!.ics)
    }

    @Test fun undoAfterAChangeElsewhereSaysSoAndWritesNothing() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val moved = writer.setDay(href, day) as TaskWriteResult.Saved
        val changed = server.objects[href]!!.ics.replace("SUMMARY:Generated a@invalid", "SUMMARY:Changed elsewhere")
        server.objects[href] = RemoteObject(href, "other", changed)
        server.log.clear()
        assertTrue(writer.clearDay(href, moved.etag, day) is TaskWriteResult.Conflict)
        assertEquals(listOf("PUT If-Match"), server.log)                        // sent, refused with 412
        assertEquals(changed, server.objects[href]!!.ics)
        assertEquals(changed, store.objectByHref(href)!!.ics)
    }

    @Test fun undoAfterARefreshBroughtANewerVersionSendsNothing() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val moved = writer.setDay(href, day) as TaskWriteResult.Saved
        val newer = RemoteObject(href, "newer", server.objects[href]!!.ics.replace("Generated a@invalid", "Newer"))
        server.objects[href] = newer
        store.upsertObjects(store.objectByHref(href)!!.calendarId, listOf(newer))
        server.log.clear()
        assertTrue(writer.clearDay(href, moved.etag, day) is TaskWriteResult.Conflict)
        assertTrue(server.log.isEmpty())
        assertEquals(newer.ics, server.objects[href]!!.ics)
    }

    @Test fun undoNeverTakesAwayAStartThisAppDidNotWrite() = runTest {
        // the same ETag but another day (cannot happen with a real server; the guard stays)
        val href = seed(list(), "a", todo("a@invalid"))
        val moved = writer.setDay(href, day) as TaskWriteResult.Saved
        server.log.clear()
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.HasDates), writer.clearDay(href, moved.etag, day.plusDays(1)))
        assertTrue(server.log.isEmpty())
    }

    @Test fun aLostAnswerMayHaveSavedAndThePhoneKeepsWhatItKnew() = runTest {
        val ics = todo("a@invalid")
        val href = seed(list(), "a", ics)
        server.loseAnswer = true
        assertEquals(TaskWriteResult.MaybeSaved, writer.setDay(href, day))
        assertEquals(ics, store.objectByHref(href)!!.ics)
        assertTrue("DTSTART;VALUE=DATE:20261120" in server.objects[href]!!.ics)       // it did reach the server
    }

    @Test fun anAnswerWithoutEtagIsReadBackNeverGuessed() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        server.omitEtag = true
        val result = writer.setDay(href, day) as TaskWriteResult.Saved
        assertEquals(server.objects[href]!!.etag, result.etag)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
    }

    @Test fun aTaskDeletedElsewhereIsGone() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        server.objects.remove(href)
        assertEquals(TaskWriteResult.Gone, writer.setDay(href, day))
        assertNull(store.objectByHref(href))
        assertEquals(TaskWriteResult.Gone, writer.setDay("/cal/tasks/never.ics", day))
    }

    @Test fun offlineNothingChanges() = runTest {
        val ics = todo("a@invalid")
        val href = seed(list(), "a", ics)
        server.offline = true
        val result = writer.setDay(href, day)
        assertTrue(result is TaskWriteResult.Failed && result.offline)
        assertEquals(ics, store.objectByHref(href)!!.ics)
        assertEquals(ics, server.objects[href]!!.ics)
    }

    @Test fun nothingIsSentForTasksThatMayNotBeMoved() = runTest {
        val cal = list()
        val dated = seed(cal, "d", todo("d@invalid", "DUE;VALUE=DATE:20261125"))
        val parked = seed(cal, "p", todo("p@invalid", "CATEGORIES:someday"))
        val repeating = seed(cal, "r", todo("r@invalid", "RRULE:FREQ=WEEKLY"))
        val people = seed(cal, "o", todo("o@invalid", "ORGANIZER:mailto:a@example.org"))
        val finished = seed(cal, "f", todo("f@invalid", "STATUS:COMPLETED"))
        val shared = seed(list(canWrite = false, href = "/cal/shared/"), "s", todo("s@invalid"))
        val events = seed(list(kind = ListKind.Events, href = "/cal/events/"), "e", todo("e@invalid"))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.HasDates), writer.setDay(dated, day))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.Parked), writer.setDay(parked, day))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.Repeating), writer.setDay(repeating, day))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.WithPeople), writer.setDay(people, day))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.Finished), writer.setDay(finished, day))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), writer.setDay(shared, day))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), writer.setDay(events, day))
        assertTrue(server.log.isEmpty())
    }

    @Test fun aParentWithOpenSubtasksMayStillGetADay() = runTest {
        val cal = list()
        val parent = seed(cal, "p", todo("p@invalid"))
        seed(cal, "c", todo("c@invalid", "RELATED-TO:p@invalid"))
        assertTrue(writer.setDay(parent, day) is TaskWriteResult.Saved)
    }
}
