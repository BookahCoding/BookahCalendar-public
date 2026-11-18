package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.ListKind
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which stored items are handed to the expander (and so to the screens and the reminders). Generated data only. */
class EventSourcesTest {
    private fun cal(id: Long, kind: ListKind, visible: Boolean = true, editable: Boolean = true) =
        CalendarInfo(id, "/c/$id/", "C$id", "#00679E", Source.Nextcloud, editable, visible, null, kind)

    private fun obj(cal: Long, name: String) = StoredObject("/c/$cal/$name.ics", cal, "e", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n")

    @Test fun taskOnlyListsAndHiddenCalendarsGiveNoEventSources() {
        val cals = listOf(cal(1, ListKind.Events), cal(2, ListKind.Tasks), cal(3, ListKind.Both), cal(4, ListKind.Events, visible = false))
        val objects = listOf(obj(1, "a"), obj(2, "t"), obj(3, "m"), obj(4, "h"))
        assertEquals(listOf("/c/1/a.ics", "/c/3/m.ics"), eventSources(objects, cals).map { it.href })
    }

    @Test fun theSourceCarriesItsCacheKeyAndReadOnlyFlag() {
        val s = eventSources(listOf(obj(1, "a")), listOf(cal(1, ListKind.Events, editable = false))).single()
        assertEquals("/c/1/a.ics|e", s.cacheKey)
        assertEquals(true, s.readOnly)
    }
}
