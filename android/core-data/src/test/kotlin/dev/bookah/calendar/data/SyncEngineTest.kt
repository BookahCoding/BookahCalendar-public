package dev.bookah.calendar.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val HOME = "/remote.php/dav/calendars/Demo/"

/** A made-up server: calendars with objects, a sync counter, and switches to break things. */
private class FakeServer : CalDavApi {
    var calendars = listOf(
        RemoteCalendar("${HOME}personal/", "Personal", "#00679E", true),
        RemoteCalendar("${HOME}contact_birthdays/", "Contact birthdays", "#E9D859", true),
    )
    val data = HashMap<String, MutableMap<String, String>>()   // calendar href -> (object href -> ics)
    var version = 1
    var failWith: Exception? = null
    var rejectTokens = false
    var multigetCalls = 0
    val seenTokens = ArrayList<String?>()

    override suspend fun listCalendars(): List<RemoteCalendar> { failWith?.let { throw it }; return calendars }

    override suspend fun syncCollection(calendarHref: String, token: String?): SyncDelta {
        failWith?.let { throw it }
        seenTokens.add(token)
        if (token != null && rejectTokens) throw SyncTokenInvalidException()
        val objs = data[calendarHref].orEmpty()
        val since = token?.substringAfterLast('/')?.toInt() ?: 0
        // everything counts as changed since the token's version, except what was marked removed
        val changed = objs.filter { (_, ics) -> !ics.startsWith("REMOVED") && versionOf(ics) > since }.map { (h, ics) -> ChangedEntry(h, "etag-${versionOf(ics)}") }
        val removed = objs.filter { (_, ics) -> ics.startsWith("REMOVED") && versionOf(ics) > since }.keys.toList()
        return SyncDelta("http://sabre.io/ns/sync/$version", changed, removed)
    }

    override suspend fun multiget(calendarHref: String, hrefs: List<String>): List<RemoteObject> {
        failWith?.let { throw it }
        multigetCalls++
        return hrefs.map { RemoteObject(it, "etag-${versionOf(data[calendarHref]!![it]!!)}", data[calendarHref]!![it]!!) }
    }

    private fun versionOf(ics: String) = ics.substringAfter("v=").substringBefore(";").toInt()
    fun put(cal: String, name: String, title: String) { version++; data.getOrPut("$HOME$cal/") { HashMap() }["$HOME$cal/$name.ics"] = "SUMMARY:$title;v=$version" }
    fun remove(cal: String, name: String) { version++; data["$HOME$cal/"]!!["$HOME$cal/$name.ics"] = "REMOVED;v=$version" }
}

class SyncEngineTest {
    private fun run(block: suspend () -> SyncReport) = runBlocking { block() }

    @Test fun firstSyncFetchesEverythingInChunks() {
        val server = FakeServer()
        for (i in 1..120) server.put("personal", "e$i", "Event $i")
        val store = MemoryCalendarStore()
        val report = run { SyncEngine(store, chunk = 50).sync(server) }
        assertEquals(2, report.calendars)
        assertEquals(120, report.objectsChanged)
        assertEquals(3, server.multigetCalls)                         // 120 objects in chunks of 50
        val personal = store.calendars().first { it.name == "Personal" }
        assertEquals(120, store.objectCount(personal.id))
        assertTrue(personal.syncToken!!.endsWith("/${server.version}"))
    }

    @Test fun contactBirthdaysStartHiddenAndReadOnly() {
        val server = FakeServer()
        val store = MemoryCalendarStore()
        run { SyncEngine(store).sync(server) }
        val b = store.calendars().first { it.name == "Contact birthdays" }
        assertFalse(b.visible); assertFalse(b.editable)
        val p = store.calendars().first { it.name == "Personal" }
        assertTrue(p.visible); assertTrue(p.editable)
    }

    @Test fun secondSyncFetchesOnlyChanges() {
        val server = FakeServer()
        for (i in 1..5) server.put("personal", "e$i", "Event $i")
        val store = MemoryCalendarStore()
        val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.multigetCalls = 0
        server.put("personal", "e3", "Event 3 changed")
        server.put("personal", "e9", "New event")
        server.remove("personal", "e1")
        val report = run { engine.sync(server) }
        assertEquals(2, report.objectsChanged); assertEquals(1, report.objectsRemoved)
        val personal = store.calendars().first { it.name == "Personal" }
        val titles = store.objects(personal.id).map { it.ics.substringAfter("SUMMARY:").substringBefore(";") }.sorted()
        assertEquals(listOf("Event 2", "Event 3 changed", "Event 4", "Event 5", "New event"), titles)
    }

    @Test fun refusedTokenMeansFullResyncAndStaleObjectsDisappear() {
        val server = FakeServer()
        for (i in 1..3) server.put("personal", "e$i", "Event $i")
        val store = MemoryCalendarStore()
        val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.remove("personal", "e2")
        // the server forgets its tokens AND no longer lists e2 at all
        server.data["${HOME}personal/"]!!.remove("${HOME}personal/e2.ics")
        server.rejectTokens = true
        val report = run { engine.sync(server) }
        // the fake keeps rejecting tokens, but the retry uses no token, which it accepts
        assertTrue(report.fullResyncs >= 1)
        val personal = store.calendars().first { it.name == "Personal" }
        assertEquals(listOf("Event 1", "Event 3"), store.objects(personal.id).map { it.ics.substringAfter("SUMMARY:").substringBefore(";") }.sorted())
    }

    @Test fun offlineKeepsTheCache() {
        val server = FakeServer()
        server.put("personal", "e1", "Event 1")
        val store = MemoryCalendarStore()
        val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.failWith = IOException("timeout")
        val report = run { engine.sync(server) }
        assertTrue(report.offline)
        val personal = store.calendars().first { it.name == "Personal" }
        assertEquals(1, store.objectCount(personal.id))
    }

    @Test fun brokenDownloadLeavesOldStateInPlace() {
        val server = FakeServer()
        server.put("personal", "e1", "Event 1")
        val store = MemoryCalendarStore()
        val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.put("personal", "e2", "Event 2")
        val failing = object : CalDavApi by server {
            override suspend fun multiget(calendarHref: String, hrefs: List<String>): List<RemoteObject> = throw IOException("connection reset")
        }
        val report = run { engine.sync(failing) }
        assertTrue(report.offline)
        val personal = store.calendars().first { it.name == "Personal" }
        assertEquals(1, store.objectCount(personal.id))
        assertTrue(personal.syncToken!!.endsWith("/2"))               // the token did not move, so the next run fetches e2 again
    }

    @Test fun calendarsRemovedOnTheServerDisappearWithTheirObjects() {
        val server = FakeServer()
        server.put("personal", "e1", "Event 1")
        val store = MemoryCalendarStore()
        val engine = SyncEngine(store)
        run { engine.sync(server) }
        server.calendars = server.calendars.filter { it.name != "Contact birthdays" }
        run { engine.sync(server) }
        assertEquals(listOf("Personal"), store.calendars().map { it.name })
    }

    @Test fun wrongPasswordIsReportedNotThrown() {
        val server = FakeServer()
        server.failWith = CalDavException(401, "The server did not accept the user name or app password.")
        val report = run { SyncEngine(MemoryCalendarStore()).sync(server) }
        assertFalse(report.offline)
        assertTrue(report.error!!.contains("app password"))
    }
}
