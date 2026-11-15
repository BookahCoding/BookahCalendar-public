package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class EventWriterSeriesTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val server = FakeCalendarServer()
    private val store = MemoryCalendarStore()
    private var uids = 0
    private val writer = EventWriter(store, server, newUid = { "split-${++uids}" }, clock = { now })

    private val tz = "BEGIN:VTIMEZONE\r\nTZID:Europe/Athens\r\nBEGIN:DAYLIGHT\r\nDTSTART:19700329T030000\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0300\r\nEND:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\nDTSTART:19701025T040000\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nTZOFFSETFROM:+0300\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
    private fun weekly(extra: String = "") = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$tz" +
        "BEGIN:VEVENT\r\nUID:w@x\r\nDTSTAMP:20261001T000000Z\r\nDTSTART;TZID=Europe/Athens:20261006T100000\r\nDTEND;TZID=Europe/Athens:20261006T110000\r\nRRULE:FREQ=WEEKLY;BYDAY=TU\r\nSUMMARY:Weekly\r\n$extra" + "END:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun at(week: Int) = LocalDate.of(2026, 10, 6).plusWeeks(week.toLong()).atTime(10, 0).atZone(athens).toInstant().toEpochMilli()
    private fun occ(week: Int) = Occurrence(at(week), at(week), at(week) + 3_600_000L)
    private val href = "/cal/a/w.ics"

    private fun calendar(canWrite: Boolean = true): Long =
        store.replaceNextcloudCalendars(listOf(RemoteCalendar("/cal/a/", "Mine", "#00679E", canWrite))).first().id

    private fun put(ics: String, name: String = href, etag: String = "e0"): Long {
        val cal = store.calendars().firstOrNull()?.id ?: calendar()
        server.objects[name] = RemoteObject(name, etag, ics)
        store.upsertObjects(cal, listOf(RemoteObject(name, etag, ics)))
        return cal
    }

    private fun starts(ics: String) = Expander().expand(listOf(ObjectSource(1, "h", ics, "k", false)), at(0) - 86_400_000L, at(10)).map { it.start }.sorted()
    private val original get() = starts(weekly())

    @Test fun editThisWritesAnOverrideWithIfMatchAndUpdatesThePhonesCopy() = runTest {
        put(weekly()); server.log.clear()
        val r = writer.updateOccurrence(href, occ(2), Scope.This, EventEdit(title = "Only this"))
        assertEquals(WriteResult.Saved(href), r)
        assertEquals(listOf("PUT If-Match"), server.log)
        val ics = store.objectByHref(href)!!.ics
        assertEquals(original, starts(ics))
        assertTrue("RECURRENCE-ID;TZID=Europe/Athens:20261020T100000" in ics)
        assertEquals(server.objects[href]!!.etag, store.objectByHref(href)!!.etag)
    }

    @Test fun deleteFollowingShortensTheSeries() = runTest {
        put(weekly())
        assertEquals(WriteResult.Saved(href), writer.deleteOccurrence(href, at(3), Scope.Following))
        assertEquals(original.filter { it < at(3) }, starts(store.objectByHref(href)!!.ics))
        assertEquals(server.objects[href]!!.ics, store.objectByHref(href)!!.ics)
    }

    @Test fun deleteFollowingFromTheFirstOccurrenceDeletesTheWholeObject() = runTest {
        put(weekly())
        assertEquals(WriteResult.Saved(href), writer.deleteOccurrence(href, at(0), Scope.Following))
        assertNull(server.objects[href]); assertNull(store.objectByHref(href))
    }

    @Test fun deleteThisAddsAnExdateAndDeleteAllRemovesTheObject() = runTest {
        put(weekly())
        assertEquals(WriteResult.Saved(href), writer.deleteOccurrence(href, at(2), Scope.This))
        assertEquals(original - at(2), starts(server.objects[href]!!.ics))
        assertEquals(WriteResult.Saved(href), writer.deleteOccurrence(href, at(2), Scope.All))
        assertNull(server.objects[href])
    }

    @Test fun editFollowingCreatesTheNewObjectFirstThenShortensTheOldOne() = runTest {
        put(weekly()); server.log.clear()
        val r = writer.updateOccurrence(href, occ(3), Scope.Following, EventEdit(title = "From now on"))
        assertEquals(WriteResult.Saved(href), r)
        assertEquals(listOf("PUT If-None-Match:*", "PUT If-Match"), server.log)                  // new object first, then the old one
        val newHref = "/cal/a/split-1.ics"
        assertEquals(setOf(href, newHref), server.objects.keys)
        assertEquals(original.filter { it < at(3) }, starts(server.objects[href]!!.ics))
        assertEquals(original.filter { it >= at(3) }, starts(server.objects[newHref]!!.ics))
        assertEquals(setOf(href, newHref), store.allObjects().map { it.href }.toSet())            // the phone's copy has both
    }

    @Test fun whenShorteningTheOldObjectIsRefusedTheNewObjectIsTakenBackSoNothingIsDoubled() = runTest {
        put(weekly())
        server.objects[href] = RemoteObject(href, "other", weekly().replace("SUMMARY:Weekly", "SUMMARY:Weekly (changed elsewhere)"))   // changed behind our back
        val r = writer.updateOccurrence(href, occ(3), Scope.Following, EventEdit(title = "X"))
        assertTrue(r is WriteResult.Conflict)
        assertEquals(setOf(href), server.objects.keys)                                            // the new object was removed again
        assertNull(store.objectByHref("/cal/a/split-1.ics"))
    }

    @Test fun afterAConflictAddMyChangesRepeatsTheOperationOnTheirVersion() = runTest {
        put(weekly())
        server.objects[href] = RemoteObject(href, "other", weekly().replace("SUMMARY:Weekly", "SUMMARY:Weekly (changed elsewhere)"))
        assertTrue(writer.updateOccurrence(href, occ(2), Scope.All, EventEdit(location = "Hall")) is WriteResult.Conflict)
        assertFalse("Hall" in server.objects[href]!!.ics)
        assertEquals(WriteResult.Saved(href), writer.updateOccurrence(href, occ(2), Scope.All, EventEdit(location = "Hall"), onTheirs = true))
        val ics = server.objects[href]!!.ics
        assertTrue("changed elsewhere" in ics && "LOCATION:Hall" in ics)
    }

    @Test fun aSeriesWithPeopleIsRefusedForEditsAndPartialDeletes() = runTest {
        put(weekly("ORGANIZER:mailto:a@example.org\r\n")); server.log.clear()
        assertTrue(writer.updateOccurrence(href, occ(1), Scope.This, EventEdit(title = "x")) is WriteResult.Refused)
        assertTrue(writer.deleteOccurrence(href, at(1), Scope.This) is WriteResult.Refused)
        assertTrue(writer.deleteOccurrence(href, at(1), Scope.Following) is WriteResult.Refused)
        assertTrue(server.log.isEmpty())
    }

    @Test fun anEventWithPeopleCanBeDeletedAsAWholeAfterTheUserWasWarned() = runTest {
        val people = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:p\r\nDTSTART:20261120T100000Z\r\nORGANIZER:mailto:a@example.org\r\nATTENDEE:mailto:b@example.org\r\nSUMMARY:Meet\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        put(people, "/cal/a/p.ics")
        assertEquals(WriteResult.Saved("/cal/a/p.ics"), writer.deleteWholeObject("/cal/a/p.ics"))
        assertNull(server.objects["/cal/a/p.ics"]); assertNull(store.objectByHref("/cal/a/p.ics"))
    }

    @Test fun deletingAWholeSeriesWithPeopleStillNeedsAWritableCalendar() = runTest {
        put(weekly("ATTENDEE:mailto:b@example.org\r\n"))
        // the same object in a calendar that cannot be written
        store.replaceNextcloudCalendars(listOf(RemoteCalendar("/cal/a/", "Mine", "#00679E", canWrite = false)))
        assertTrue(writer.deleteWholeObject(href) is WriteResult.Refused)
        assertTrue(server.objects.containsKey(href))
    }

    @Test fun aTaskIsNeverDeletedByTheWholeObjectDelete() = runTest {
        put("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:t\r\nSUMMARY:T\r\nEND:VTODO\r\nEND:VCALENDAR\r\n", "/cal/a/t.ics")
        assertTrue(writer.deleteWholeObject("/cal/a/t.ics") is WriteResult.Refused)
    }

    @Test fun anImpossibleChangeIsRefusedBeforeAnythingIsSent() = runTest {
        put(weekly()); server.log.clear()
        val r = writer.updateOccurrence(href, occ(1), Scope.All, EventEdit(whenAt = EventWhen.AllDay(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 13))))
        assertTrue(r is WriteResult.Refused)
        assertTrue(server.log.isEmpty())
    }
}
