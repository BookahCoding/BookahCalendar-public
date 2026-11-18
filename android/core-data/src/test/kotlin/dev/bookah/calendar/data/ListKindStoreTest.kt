package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list kind (events, tasks, both) is kept per calendar and follows the server; the owner's show/hide choice survives every re-sync. */
class ListKindStoreTest {
    private val events = RemoteCalendar("/c/personal/", "Personal", "#00679E", true)
    private val tasks = RemoteCalendar("/c/tasks/", "Tasks", "#3E8E41", true, kind = ListKind.Tasks)
    private val mixed = RemoteCalendar("/c/mixed/", "Mixed", "#7A5CA8", true, kind = ListKind.Both)

    @Test fun aRemoteCalendarIsAnEventCalendarUnlessSaidOtherwise() = assertEquals(ListKind.Events, events.kind)

    @Test fun theKindIsStored() {
        val store = MemoryCalendarStore()
        val kinds = store.replaceNextcloudCalendars(listOf(events, tasks, mixed)).associate { it.name to it.kind }
        assertEquals(mapOf("Personal" to ListKind.Events, "Tasks" to ListKind.Tasks, "Mixed" to ListKind.Both), kinds)
    }

    @Test fun aHiddenTaskListStaysHiddenAfterAResync() {
        val store = MemoryCalendarStore()
        val id = store.replaceNextcloudCalendars(listOf(events, tasks)).first { it.name == "Tasks" }.id
        store.setVisible(id, false)
        val again = store.replaceNextcloudCalendars(listOf(events, tasks.copy(name = "Tasks renamed"))).first { it.id == id }
        assertFalse(again.visible)
        assertEquals(ListKind.Tasks, again.kind)
        assertEquals("Tasks renamed", again.name)
    }

    @Test fun theKindFollowsTheServerWhenItChanges() {
        val store = MemoryCalendarStore()
        val id = store.replaceNextcloudCalendars(listOf(mixed.copy(kind = ListKind.Events))).single().id
        store.setVisible(id, false)
        val now = store.replaceNextcloudCalendars(listOf(mixed)).single()
        assertEquals(id, now.id)
        assertEquals(ListKind.Both, now.kind)
        assertFalse(now.visible)
    }

    @Test fun onlyListsThatHoldEventsAreOfferedForNewEvents() {
        assertTrue(ListKind.Events.holdsEvents); assertTrue(ListKind.Both.holdsEvents); assertFalse(ListKind.Tasks.holdsEvents)
        assertFalse(ListKind.Events.holdsTasks); assertTrue(ListKind.Both.holdsTasks); assertTrue(ListKind.Tasks.holdsTasks)
    }

    @Test fun feedsAreEventCalendars() {
        val store = MemoryCalendarStore()
        assertEquals(ListKind.Events, store.syncFeedCalendars(listOf(FeedCalendar("google:x", "Feed", "#7A5CA8"))).single().kind)
    }
}
