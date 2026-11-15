package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicatesTest {
    private val nc = CalendarInfo(1, "/cal/a/", "Mine", "#00679E", Source.Nextcloud, true, visible = false, syncToken = null)   // hidden on purpose
    private val g = CalendarInfo(2, "google:f1", "Feed", "#7A5CA8", Source.Google, false, true, null)
    private fun obj(cal: Long, uid: String) = StoredObject("h-$cal-$uid", cal, "e", "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:$uid\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")

    @Test fun googleCopyOfANextcloudEventIsLeftOutEvenWhenThatCalendarIsHidden() {
        val (kept, hidden) = dropGoogleDuplicates(listOf(obj(1, "a@x"), obj(2, "a@x"), obj(2, "only-google@x")), listOf(nc, g))
        assertEquals(1, hidden)
        assertEquals(listOf("h-1-a@x", "h-2-only-google@x"), kept.map { it.href })
    }

    @Test fun nextcloudEventsAreNeverDropped() {
        val (kept, hidden) = dropGoogleDuplicates(listOf(obj(1, "a@x"), obj(1, "b@x")), listOf(nc, g))
        assertEquals(0, hidden)
        assertEquals(2, kept.size)
    }

    @Test fun noNextcloudCalendarsMeansEverythingFromGoogleStays() {
        val (kept, hidden) = dropGoogleDuplicates(listOf(obj(2, "a@x"), obj(2, "b@x")), listOf(g))
        assertEquals(0, hidden)
        assertEquals(2, kept.size)
    }
}
