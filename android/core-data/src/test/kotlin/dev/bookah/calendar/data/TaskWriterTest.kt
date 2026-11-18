package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The tick and its undo against a fake server that enforces If-Match like Nextcloud (FakeCalendarServer in EventWriterTest). Generated tasks only. */
class TaskWriterTest {
    private val server = FakeCalendarServer()
    private val store = MemoryCalendarStore()
    private var now = Instant.parse("2026-11-18T10:15:30Z")
    private val writer = TaskWriter(store, server, clock = { now })

    private fun todo(uid: String, vararg lines: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Generated $uid\r\n" +
        lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n"

    private fun list(kind: ListKind = ListKind.Tasks, canWrite: Boolean = true, href: String = "/cal/tasks/"): Long =
        store.replaceNextcloudCalendars(store.calendars().filter { it.source == Source.Nextcloud }.map { RemoteCalendar(it.href, it.name, it.colorHex, it.editable, it.kind) } +
            RemoteCalendar(href, "Tasks", "#3E8E41", canWrite, kind)).first { it.href == href }.id

    /** The same object on the server and in the phone's copy, with one ETag. */
    private fun seed(cal: Long, name: String, ics: String, etag: String = "e0"): String {
        val href = store.calendars().first { it.id == cal }.href + "$name.ics"
        server.objects[href] = RemoteObject(href, etag, ics)
        store.upsertObjects(cal, listOf(RemoteObject(href, etag, ics)))
        return href
    }

    @Test fun aTickIsWrittenWithIfMatchAndThePhoneKeepsTheServersEtag() = runTest {
        val href = seed(list(), "a", todo("a@invalid", "DUE;VALUE=DATE:20261120"))
        val result = writer.complete(href)
        assertTrue(result is TaskWriteResult.Saved)
        assertEquals(listOf("PUT If-Match"), server.log)
        val etag = server.objects[href]!!.etag
        assertEquals(etag, (result as TaskWriteResult.Saved).etag)
        assertEquals(etag, store.objectByHref(href)!!.etag)
        val ics = server.objects[href]!!.ics
        assertTrue("STATUS:COMPLETED\r\n" in ics && "COMPLETED:20261118T101530Z\r\n" in ics && "PERCENT-COMPLETE:100\r\n" in ics)
        assertEquals(ics, store.objectByHref(href)!!.ics)
        assertTrue(TaskReader.parse(1, href, store.objectByHref(href)!!.ics, java.time.ZoneOffset.UTC).single().done)
    }

    @Test fun aMixedListIsFineToo() = runTest {
        val href = seed(list(kind = ListKind.Both, href = "/cal/mixed/"), "m", todo("m@invalid"))
        assertTrue(writer.complete(href) is TaskWriteResult.Saved)
    }

    @Test fun undoNamesTheEtagOfTheTickAndOpensTheTaskAgain() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val done = writer.complete(href) as TaskWriteResult.Saved
        now = now.plusSeconds(5)
        val back = writer.reopen(href, done.etag, done.before)
        assertTrue(back is TaskWriteResult.Saved)
        assertEquals(listOf("PUT If-Match", "PUT If-Match"), server.log)
        val ics = server.objects[href]!!.ics
        assertFalse("STATUS:" in ics)                                   // it had no STATUS before the tick, so none after the undo
        assertFalse("COMPLETED:" in ics)
        assertFalse("PERCENT-COMPLETE" in ics)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
        assertFalse(TaskReader.parse(1, href, ics, java.time.ZoneOffset.UTC).single().done)
    }

    @Test fun undoBringsBackInProcessAndItsPercent() = runTest {
        val href = seed(list(), "a", todo("a@invalid", "STATUS:IN-PROCESS", "PERCENT-COMPLETE:40"))
        val done = writer.complete(href) as TaskWriteResult.Saved
        assertEquals(TaskDone.BeforeTick("IN-PROCESS", "40"), done.before)
        assertTrue(writer.reopen(href, done.etag, done.before) is TaskWriteResult.Saved)
        val ics = server.objects[href]!!.ics
        assertTrue("STATUS:IN-PROCESS\r\n" in ics && "PERCENT-COMPLETE:40\r\n" in ics)
        assertFalse(Regex("^COMPLETED:", RegexOption.MULTILINE).containsMatchIn(ics))
    }

    @Test fun anOpenSubtaskIsFoundEvenWhenItsLongParentUidIsFolded() = runTest {
        val cal = list()
        val longUid = "generated-parent-" + "x".repeat(70) + "@invalid"
        val parent = seed(cal, "p", todo(longUid))
        // RELATED-TO folded at 75 bytes, as a writer must: the UID is split over two physical lines
        val related = TaskPatch.fold("RELATED-TO:$longUid")
        assertTrue("\r\n " in related)
        seed(cal, "c", todo("c@invalid", related))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.OpenSubtasks), writer.complete(parent))
        assertTrue(server.log.isEmpty())
    }

    @Test fun aLostAnswerMayHaveSavedAndIsNotCalledNothingChanged() = runTest {
        val ics = todo("a@invalid")
        val href = seed(list(), "a", ics)
        server.loseAnswer = true
        assertEquals(TaskWriteResult.MaybeSaved, writer.complete(href))
        assertEquals(ics, store.objectByHref(href)!!.ics)                // the phone keeps what it knows; a refresh brings the truth
        assertTrue("STATUS:COMPLETED" in server.objects[href]!!.ics)     // it did reach the server
    }

    @Test fun anAnswerWithoutEtagThatCannotBeReadBackMayHaveSaved() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        server.omitEtag = true
        server.getFindsNothing = true
        assertEquals(TaskWriteResult.MaybeSaved, writer.complete(href))
        assertEquals("e0", store.objectByHref(href)!!.etag)
    }

    @Test fun onlyAFailureBeforeTheRequestLeftThePhoneIsNothingChanged() {
        assertTrue(java.net.ConnectException("x").beforeRequestSent())
        assertTrue(java.net.UnknownHostException("x").beforeRequestSent())
        assertTrue(java.net.NoRouteToHostException("x").beforeRequestSent())
        assertTrue(java.net.SocketTimeoutException("connect timed out").beforeRequestSent())
        assertTrue(javax.net.ssl.SSLHandshakeException("x").beforeRequestSent())
        assertFalse(java.net.SocketTimeoutException("timeout").beforeRequestSent())
        assertFalse(java.net.SocketTimeoutException("Read timed out").beforeRequestSent())
        assertFalse(java.io.IOException("unexpected end of stream").beforeRequestSent())
    }

    @Test fun aChangeElsewhereIsAConflictNothingIsOverwrittenAndThePhoneShowsTheirVersion() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val theirs = todo("a@invalid").replace("SUMMARY:Generated a@invalid", "SUMMARY:Changed elsewhere")
        server.objects[href] = RemoteObject(href, "other", theirs)
        val result = writer.complete(href)
        assertTrue(result is TaskWriteResult.Conflict)
        assertEquals(theirs, server.objects[href]!!.ics)                         // nothing written
        assertEquals("other", store.objectByHref(href)!!.etag)                   // the phone now has their version
        assertEquals(theirs, store.objectByHref(href)!!.ics)
        // and a second tap works on top of it
        assertTrue(writer.complete(href) is TaskWriteResult.Saved)
        assertTrue("SUMMARY:Changed elsewhere" in server.objects[href]!!.ics)
    }

    @Test fun undoAfterAChangeElsewhereSaysSoAndWritesNothing() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val done = writer.complete(href) as TaskWriteResult.Saved
        val changed = server.objects[href]!!.ics.replace("SUMMARY:Generated a@invalid", "SUMMARY:Changed elsewhere")
        server.objects[href] = RemoteObject(href, "other", changed)
        server.log.clear()
        assertTrue(writer.reopen(href, done.etag, done.before) is TaskWriteResult.Conflict)
        assertEquals(listOf("PUT If-Match"), server.log)                          // sent, refused with 412
        assertEquals(changed, server.objects[href]!!.ics)
        assertEquals(changed, store.objectByHref(href)!!.ics)
    }

    @Test fun undoAfterARefreshBroughtANewerVersionSendsNothing() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        val done = writer.complete(href) as TaskWriteResult.Saved
        // a refresh meanwhile: the phone's copy holds a newer version
        val newer = RemoteObject(href, "newer", server.objects[href]!!.ics.replace("Generated a@invalid", "Newer"))
        server.objects[href] = newer
        store.upsertObjects(store.objectByHref(href)!!.calendarId, listOf(newer))
        server.log.clear()
        assertTrue(writer.reopen(href, done.etag, done.before) is TaskWriteResult.Conflict)
        assertTrue(server.log.isEmpty())
        assertEquals(newer.ics, server.objects[href]!!.ics)
    }

    @Test fun aTaskDeletedElsewhereIsGoneAndLeavesThePhone() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        server.objects.remove(href)
        assertEquals(TaskWriteResult.Gone, writer.complete(href))
        assertNull(store.objectByHref(href))
        assertEquals(TaskWriteResult.Gone, writer.complete("/cal/tasks/never.ics"))
    }

    @Test fun aNetworkFailureLeavesThePhonesCopyUnchanged() = runTest {
        val ics = todo("a@invalid")
        val href = seed(list(), "a", ics)
        server.offline = true
        val result = writer.complete(href)
        assertTrue(result is TaskWriteResult.Failed && result.offline)
        assertEquals(ics, store.objectByHref(href)!!.ics)
        assertEquals("e0", store.objectByHref(href)!!.etag)
        assertEquals(ics, server.objects[href]!!.ics)
    }

    @Test fun anAnswerWithoutEtagIsReadBackNeverGuessed() = runTest {
        val href = seed(list(), "a", todo("a@invalid"))
        server.omitEtag = true
        val result = writer.complete(href) as TaskWriteResult.Saved
        assertEquals(server.objects[href]!!.etag, result.etag)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
    }

    @Test fun nothingIsSentForTasksThatAreNotPlain() = runTest {
        val cal = list()
        val repeating = seed(cal, "r", todo("r@invalid", "DTSTART;VALUE=DATE:20261120", "RRULE:FREQ=WEEKLY"))
        val parent = seed(cal, "p", todo("p@invalid"))
        seed(cal, "c", todo("c@invalid", "RELATED-TO:p@invalid"))
        val people = seed(cal, "o", todo("o@invalid", "ORGANIZER:mailto:a@example.org"))
        val finished = seed(cal, "f", todo("f@invalid", "STATUS:COMPLETED"))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.Repeating), writer.complete(repeating))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.OpenSubtasks), writer.complete(parent))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.WithPeople), writer.complete(people))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.Finished), writer.complete(finished))
        assertTrue(server.log.isEmpty())
    }

    @Test fun aParentWhoseSubtasksAreDoneCanBeTicked() = runTest {
        val cal = list()
        val parent = seed(cal, "p", todo("p@invalid"))
        seed(cal, "c", todo("c@invalid", "RELATED-TO:p@invalid", "STATUS:COMPLETED"))
        assertTrue(writer.complete(parent) is TaskWriteResult.Saved)
    }

    @Test fun anOpenSubtaskInAHiddenListStillCounts() = runTest {
        val cal = list()
        val other = list(href = "/cal/other/")
        store.setVisible(other, false)
        val parent = seed(cal, "p", todo("p@invalid"))
        seed(other, "c", todo("c@invalid", "RELATED-TO;RELTYPE=PARENT:p@invalid"))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.OpenSubtasks), writer.complete(parent))
    }

    @Test fun readOnlyListsEventCalendarsAndFeedsAreNeverWritten() = runTest {
        val shared = seed(list(canWrite = false, href = "/cal/shared/"), "s", todo("s@invalid"))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), writer.complete(shared))
        val events = seed(list(kind = ListKind.Events, href = "/cal/events/"), "e", todo("e@invalid"))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), writer.complete(events))
        val feed = store.syncFeedCalendars(listOf(FeedCalendar("google:f", "Feed", "#7A5CA8"))).first { it.source == Source.Google }.id
        store.upsertObjects(feed, listOf(RemoteObject("google:f/x", "f1", todo("x@invalid"))))
        assertEquals(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), writer.complete("google:f/x"))
        assertTrue(server.log.isEmpty())
    }
}
