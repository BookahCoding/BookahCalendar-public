package dev.bookah.calendar.debug

import dev.bookah.calendar.data.CalDavApi
import dev.bookah.calendar.data.CalDavException
import dev.bookah.calendar.data.CalendarStore
import dev.bookah.calendar.data.ChangedEntry
import dev.bookah.calendar.data.PreconditionFailedException
import dev.bookah.calendar.data.PutResult
import dev.bookah.calendar.data.RemoteCalendar
import dev.bookah.calendar.data.RemoteObject
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.SyncDelta

/**
 * A calendar server in memory, for debug builds only (this file is in the debug source set; release builds do not contain it).
 * It enforces If-Match / If-None-Match like Nextcloud, so the app's create, edit, delete, conflict and sync paths can be tried on the emulator.
 */
class FakeServer(private val calendars: List<RemoteCalendar>, seed: List<RemoteObject>) : CalDavApi {
    private val objects = LinkedHashMap<String, RemoteObject>().apply { seed.forEach { put(it.href, it) } }
    private var counter = 0

    /** `am broadcast -a dev.bookah.calendar.FAKE_OFFLINE --ez on true`: every write fails as if the server could not be reached. */
    @Volatile var offline = false

    /** `am broadcast -a dev.bookah.calendar.FAKE_OFFLINE --ez lose true`: writes are stored, but their answer is lost (a read timeout). */
    @Volatile var loseAnswer = false

    override suspend fun listCalendars() = calendars

    override suspend fun syncCollection(calendarHref: String, token: String?) =
        SyncDelta("token-${counter}", objects.values.filter { it.href.startsWith(calendarHref) }.map { ChangedEntry(it.href, it.etag) }, emptyList())

    override suspend fun multiget(calendarHref: String, hrefs: List<String>) = hrefs.mapNotNull { objects[it] }

    override suspend fun put(objectHref: String, ics: String, ifMatch: String?): PutResult {
        if (offline) throw java.net.ConnectException("fake server set offline")
        val existing = objects[objectHref]
        if (ifMatch == null && existing != null) throw PreconditionFailedException()
        if (ifMatch != null && existing == null) throw CalDavException(404, "gone")
        if (ifMatch != null && existing!!.etag != ifMatch) throw PreconditionFailedException()
        val etag = "d${++counter}"
        objects[objectHref] = RemoteObject(objectHref, etag, ics)
        if (loseAnswer) throw java.net.SocketTimeoutException("timeout")
        return PutResult(etag)
    }

    override suspend fun delete(objectHref: String, ifMatch: String) {
        val existing = objects[objectHref] ?: throw CalDavException(404, "gone")
        if (existing.etag != ifMatch) throw PreconditionFailedException()
        objects.remove(objectHref)
    }

    override suspend fun get(objectHref: String) = objects[objectHref]

    /** The STATUS, PERCENT-COMPLETE and COMPLETED lines of the generated item with this title, as the fake server holds it (for emulator checks). */
    fun tickLines(title: String): String {
        val hit = objects.values.firstOrNull { "SUMMARY:$title\r\n" in it.ics } ?: return "not found"
        return hit.ics.split("\r\n").filter { it.startsWith("STATUS") || it.startsWith("PERCENT-COMPLETE") || it.startsWith("COMPLETED") }.joinToString(" | ").ifEmpty { "none" }
    }

    /** Changes an event or a task "elsewhere": a new ETag and a marker in the title, so the app's next write to it is refused with 412. */
    fun touch(title: String) {
        val hit = objects.values.firstOrNull { "SUMMARY:$title" in it.ics } ?: return
        objects[hit.href] = RemoteObject(hit.href, "d${++counter}", hit.ics.replace("SUMMARY:$title", "SUMMARY:$title (changed elsewhere)"))
    }

    companion object {
        @Volatile var current: FakeServer? = null

        fun from(store: CalendarStore): FakeServer {
            val cals = store.calendars().filter { it.source == Source.Nextcloud }
            val seed = cals.flatMap { c -> store.objects(c.id).map { RemoteObject(it.href, it.etag, it.ics) } }
            // the kind and the rights are kept, or the first fake sync would turn a task list into an event calendar or a read-only share into a writable one
            return FakeServer(cals.map { RemoteCalendar(it.href, it.name, it.colorHex, canWrite = it.editable, kind = it.kind) }, seed)
        }
    }
}
