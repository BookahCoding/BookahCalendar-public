package dev.bookah.calendar.data

/** Where a calendar comes from. Nextcloud calendars can be edited (Phase 3); Google feeds never can. */
enum class Source { Nextcloud, Google }

/**
 * What a Nextcloud collection holds, from its supported-calendar-component-set: events (VEVENT), tasks (VTODO, a task list made by the
 * Nextcloud Tasks app) or both. Stored as its position, so the order must never change (Events = 0 is the stored default).
 */
enum class ListKind {
    Events, Tasks, Both;

    /** New events may be put here (a task-only list would refuse them). */
    val holdsEvents: Boolean get() = this != Tasks
    val holdsTasks: Boolean get() = this != Events
}

/**
 * One calendar shown in the app.
 * [href] is the server's own path (taken from its answer, never built from the login name: the server writes the user id
 * with a different letter case). For a Google feed it is "google:<feed id>".
 */
data class CalendarInfo(
    val id: Long,
    val href: String,
    val name: String,
    /** "#RRGGBB" as set in Nextcloud, or the feed colour; shown exactly as it is. */
    val colorHex: String,
    val source: Source,
    /** false for Google and for Contact birthdays (Nextcloud rebuilds that one from Contacts). */
    val editable: Boolean,
    val visible: Boolean,
    val syncToken: String?,
    /** Events, tasks or both; feeds are always events. */
    val kind: ListKind = ListKind.Events,
)

/** A calendar as the server describes it. */
data class RemoteCalendar(
    val href: String,
    val name: String,
    val colorHex: String?,
    val canWrite: Boolean,
    val kind: ListKind = ListKind.Events,
)

/** A read-only feed shown as a calendar. [href] is "google:<feed id>". */
data class FeedCalendar(val href: String, val name: String, val colorHex: String)

/** One stored calendar object: the raw iCalendar text with its ETag. */
data class StoredObject(val href: String, val calendarId: Long, val etag: String, val ics: String)

/** What a sync-collection request returned. [removed] are hrefs the server says are gone. */
data class SyncDelta(val newToken: String?, val changed: List<ChangedEntry>, val removed: List<String>)
data class ChangedEntry(val href: String, val etag: String)

/** A fetched calendar object. */
data class RemoteObject(val href: String, val etag: String, val ics: String)

/** What one sync run did, for the "last synced" line and for tests. */
data class SyncReport(
    val calendars: Int,
    val objectsChanged: Int,
    val objectsRemoved: Int,
    val fullResyncs: Int,
    val offline: Boolean,
    val error: String? = null,
    /** The HTTP status behind [error] (401 wrong password, 403, 404 ...), 0 when the server did not give one: the app shows its own translated words for it. */
    val errorCode: Int = 0,
)

/** The server refused the stored sync token (403 or 409): the calendar must be fetched in full again. */
class SyncTokenInvalidException : Exception("sync token no longer valid")

/** What a successful save returned: the object's new ETag, or null when the server did not send one (the object is then fetched again). */
data class PutResult(val etag: String?)

/** The server refused a save or delete because the object is no longer the version we read (HTTP 412). */
class PreconditionFailedException : Exception("the event was changed on the server")

/** The server answered but not as a calendar server should (wrong address, wrong password, ...). */
class CalDavException(val code: Int, message: String) : Exception(message)
