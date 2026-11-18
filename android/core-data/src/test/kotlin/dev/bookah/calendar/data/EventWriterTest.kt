package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset

/** A tiny calendar server in memory: every save gets a new ETag, and If-Match / If-None-Match are enforced like the real one. */
internal class FakeCalendarServer : CalDavApi {
    val objects = LinkedHashMap<String, RemoteObject>()
    var counter = 0
    var offline = false
    var omitEtag = false
    val log = ArrayList<String>()

    override suspend fun listCalendars() = emptyList<RemoteCalendar>()
    override suspend fun syncCollection(calendarHref: String, token: String?) = SyncDelta(null, emptyList(), emptyList())
    override suspend fun multiget(calendarHref: String, hrefs: List<String>) = hrefs.mapNotNull { objects[it] }

    override suspend fun put(objectHref: String, ics: String, ifMatch: String?): PutResult {
        log += "PUT ${if (ifMatch == null) "If-None-Match:*" else "If-Match"}"
        if (offline) throw IOException("offline")
        val existing = objects[objectHref]
        if (ifMatch == null && existing != null) throw PreconditionFailedException()
        if (ifMatch != null && existing == null) throw CalDavException(404, "gone")
        if (ifMatch != null && existing!!.etag != ifMatch) throw PreconditionFailedException()
        val etag = "e${++counter}"
        objects[objectHref] = RemoteObject(objectHref, etag, ics)
        return PutResult(if (omitEtag) null else etag)
    }

    override suspend fun delete(objectHref: String, ifMatch: String) {
        log += "DELETE"
        if (offline) throw IOException("offline")
        val existing = objects[objectHref] ?: throw CalDavException(404, "gone")
        if (existing.etag != ifMatch) throw PreconditionFailedException()
        objects.remove(objectHref)
    }

    override suspend fun get(objectHref: String) = objects[objectHref]
}

class EventWriterTest {
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val start = LocalDate.of(2026, 11, 20).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val server = FakeCalendarServer()
    private val store = MemoryCalendarStore()
    private var uidCounter = 0
    private val writer = EventWriter(store, server, newUid = { "uid-${++uidCounter}" }, clock = { now })
    private val draft = EventDraft("Dentist", EventWhen.Timed(start, start + 3_600_000L), "Clinic", "notes", listOf(15))

    private fun calendar(canWrite: Boolean = true, href: String = "/cal/a/"): Long {
        val list = store.replaceNextcloudCalendars(listOf(RemoteCalendar(href, "Mine", "#00679E", canWrite)))
        return list.first { it.href == href }.id
    }

    private suspend fun created(calendarId: Long): String = (writer.create(calendarId, draft) as WriteResult.Saved).href

    @Test fun aNewEventIsSavedWithIfNoneMatchAndAppearsInThePhonesCopy() = runTest {
        val cal = calendar()
        val result = writer.create(cal, draft)
        assertEquals(WriteResult.Saved("/cal/a/uid-1.ics"), result)
        assertEquals(listOf("PUT If-None-Match:*"), server.log)
        val local = store.objectByHref("/cal/a/uid-1.ics")!!
        assertEquals(server.objects["/cal/a/uid-1.ics"]!!.etag, local.etag)         // the server's ETag
        assertTrue("SUMMARY:Dentist" in local.ics)
    }

    @Test fun anEventCannotBeAddedToATaskOnlyList() = runTest {
        val tasks = store.replaceNextcloudCalendars(listOf(RemoteCalendar("/cal/tasks/", "Tasks", "#3E8E41", true, kind = ListKind.Tasks))).single().id
        assertTrue(writer.create(tasks, draft) is WriteResult.Refused)
        assertTrue(server.log.isEmpty())
    }

    @Test fun anEventCannotBeAddedToAReadOnlyOrFeedCalendar() = runTest {
        val readOnly = calendar(canWrite = false)
        assertTrue(writer.create(readOnly, draft) is WriteResult.Refused)
        val feed = store.syncFeedCalendars(listOf(FeedCalendar("google:f", "Feed", "#7A5CA8"))).first { it.source == Source.Google }.id
        assertTrue(writer.create(feed, draft) is WriteResult.Refused)
        assertTrue(server.log.isEmpty())
    }

    @Test fun anEndBeforeTheStartIsRefusedBeforeAnythingIsSent() = runTest {
        val cal = calendar()
        val bad = draft.copy(whenAt = EventWhen.Timed(start + 1000, start))
        assertTrue(writer.create(cal, bad) is WriteResult.Refused)
        assertTrue(server.log.isEmpty())
    }

    @Test fun anEditUsesTheEtagReadFromThePhonesCopyAtThatMoment() = runTest {
        val href = created(calendar())
        server.log.clear()
        assertEquals(WriteResult.Saved(href), writer.update(href, EventEdit(title = "Dentist 2")))
        assertEquals(listOf("PUT If-Match"), server.log)
        assertTrue("SUMMARY:Dentist 2" in store.objectByHref(href)!!.ics)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
        assertTrue("SEQUENCE:1" in server.objects[href]!!.ics)
    }

    @Test fun whenTheServersAnswerHasNoEtagTheObjectIsReadBackNeverGuessed() = runTest {
        val cal = calendar()
        server.omitEtag = true
        val href = created(cal)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
    }

    @Test fun aChangeMadeElsewhereIsAConflictAndNothingIsWritten() = runTest {
        val href = created(calendar())
        // somebody else changes the event on the server
        server.objects[href] = RemoteObject(href, "other", server.objects[href]!!.ics.replace("Dentist", "Dentist (moved)"))
        server.log.clear()
        val result = writer.update(href, EventEdit(location = "My place"))
        assertTrue(result is WriteResult.Conflict)
        assertTrue("Dentist (moved)" in (result as WriteResult.Conflict).theirs.ics)
        assertFalse("My place" in server.objects[href]!!.ics)                        // nothing written
        assertTrue("SUMMARY:Dentist\r\n" in store.objectByHref(href)!!.ics)         // the phone's copy is unchanged too
    }

    @Test fun keepMyChangesAppliesOnlyMyFieldsOnTopOfTheirVersion() = runTest {
        val href = created(calendar())
        server.objects[href] = RemoteObject(href, "other", server.objects[href]!!.ics.replace("Dentist", "Dentist (moved)"))
        assertTrue(writer.update(href, EventEdit(location = "My place")) is WriteResult.Conflict)
        assertEquals(WriteResult.Saved(href), writer.updateOnTheirs(href, EventEdit(location = "My place")))
        val ics = server.objects[href]!!.ics
        assertTrue("SUMMARY:Dentist (moved)" in ics)        // their change kept
        assertTrue("LOCATION:My place" in ics)              // mine added
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
    }

    @Test fun useTheirVersionDiscardsMyEdit() = runTest {
        val href = created(calendar())
        server.objects[href] = RemoteObject(href, "other", server.objects[href]!!.ics.replace("Dentist", "Dentist (moved)"))
        assertTrue(writer.update(href, EventEdit(title = "Mine")) is WriteResult.Conflict)
        assertEquals(WriteResult.Saved(href), writer.keepTheirs(href))
        assertTrue("SUMMARY:Dentist (moved)" in store.objectByHref(href)!!.ics)
        assertFalse("Mine" in server.objects[href]!!.ics)
    }

    @Test fun anEventDeletedElsewhereIsReportedAsGoneAndRemovedFromThePhone() = runTest {
        val href = created(calendar())
        server.objects.remove(href)
        assertEquals(WriteResult.Gone, writer.update(href, EventEdit(title = "x")))
        assertNull(store.objectByHref(href))
    }

    @Test fun deleteRemovesItOnTheServerAndInThePhonesCopy() = runTest {
        val href = created(calendar())
        assertEquals(WriteResult.Saved(href), writer.delete(href))
        assertNull(server.objects[href]); assertNull(store.objectByHref(href))
    }

    @Test fun aDeleteOfAnEventChangedElsewhereIsAConflictUntilTheUserConfirms() = runTest {
        val href = created(calendar())
        server.objects[href] = RemoteObject(href, "other", server.objects[href]!!.ics)
        assertTrue(writer.delete(href) is WriteResult.Conflict)
        assertTrue(server.objects.containsKey(href))                  // still there
        assertEquals(WriteResult.Saved(href), writer.deleteOnTheirs(href))
        assertNull(server.objects[href])
    }

    @Test fun repeatingEventsAndEventsWithPeopleAreNeverWritten() = runTest {
        val cal = calendar()
        val repeating = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:r\r\nDTSTART:20261120T100000Z\r\nRRULE:FREQ=WEEKLY\r\nSUMMARY:R\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val people = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:p\r\nDTSTART:20261120T100000Z\r\nORGANIZER:mailto:a@example.org\r\nSUMMARY:P\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        for ((name, ics) in listOf("/cal/a/r.ics" to repeating, "/cal/a/p.ics" to people)) {
            server.objects[name] = RemoteObject(name, "e0", ics)
            store.upsertObjects(cal, listOf(RemoteObject(name, "e0", ics)))
            assertTrue(writer.update(name, EventEdit(title = "x")) is WriteResult.Refused)
            assertTrue(writer.delete(name) is WriteResult.Refused)
        }
        assertTrue(server.log.isEmpty())
        assertEquals(2, server.objects.size)
    }

    @Test fun whenOfflineNothingChangesAndTheAnswerSaysSo() = runTest {
        val cal = calendar()
        server.offline = true
        val result = writer.create(cal, draft)
        assertTrue(result is WriteResult.Failed && (result as WriteResult.Failed).offline)
        assertTrue(store.objects(cal).isEmpty())
    }

    @Test fun anEventNotInThePhonesCopyIsGone() = runTest {
        calendar()
        assertEquals(WriteResult.Gone, writer.update("/cal/a/none.ics", EventEdit(title = "x")))
    }
}
