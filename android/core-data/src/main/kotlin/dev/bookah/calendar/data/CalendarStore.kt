package dev.bookah.calendar.data

/**
 * Everything the app keeps on the phone about calendars. The real one is SQLite ([SqliteCalendarStore]);
 * tests use [MemoryCalendarStore].
 */
interface CalendarStore {
    /** Adds new calendars, updates name/colour/rights of known ones (keeping their show/hide choice), removes the ones the server no longer lists (with their objects). */
    fun replaceNextcloudCalendars(remote: List<RemoteCalendar>): List<CalendarInfo>
    /**
     * Adds read-only feed calendars (Google), updates name/colour of known ones (keeping show/hide), removes feed calendars that are no longer
     * configured, with their objects. The feed's ETag is kept in the calendar's sync token. Nextcloud calendars are never touched.
     */
    fun syncFeedCalendars(feeds: List<FeedCalendar>): List<CalendarInfo>
    fun calendars(): List<CalendarInfo>
    fun setVisible(calendarId: Long, visible: Boolean)
    fun setSyncToken(calendarId: Long, token: String?)

    fun objects(calendarId: Long): List<StoredObject>
    fun allObjects(): List<StoredObject>

    /** One stored object by its server address, or null. */
    fun objectByHref(href: String): StoredObject?
    fun upsertObjects(calendarId: Long, objects: List<RemoteObject>)
    fun deleteObjects(calendarId: Long, hrefs: List<String>)
    fun clearObjects(calendarId: Long)
    fun objectCount(calendarId: Long): Int

    /** Increases whenever objects or calendars change, so screens know when to expand again. */
    fun version(): Long

    fun clearAll()
}

/** A plain in-memory store for unit tests. */
class MemoryCalendarStore : CalendarStore {
    private var nextId = 1L
    private var ver = 0L
    private val cals = LinkedHashMap<Long, CalendarInfo>()
    private val objs = LinkedHashMap<String, StoredObject>()

    override fun replaceNextcloudCalendars(remote: List<RemoteCalendar>): List<CalendarInfo> {
        val byHref = cals.values.filter { it.source == Source.Nextcloud }.associateBy { it.href }
        val keep = HashSet<String>()
        for (r in remote) {
            keep.add(r.href)
            val old = byHref[r.href]
            val editable = r.canWrite && !isContactBirthdays(r.href)
            if (old == null) {
                val id = nextId++
                cals[id] = CalendarInfo(id, r.href, r.name, r.colorHex ?: DEFAULT_COLOR, Source.Nextcloud, editable, visible = !isContactBirthdays(r.href), syncToken = null)
            } else {
                cals[old.id] = old.copy(name = r.name, colorHex = r.colorHex ?: old.colorHex, editable = editable)
            }
        }
        for (old in byHref.values) if (old.href !in keep) { cals.remove(old.id); objs.values.removeAll { it.calendarId == old.id } }
        ver++
        return calendars()
    }

    override fun syncFeedCalendars(feeds: List<FeedCalendar>): List<CalendarInfo> {
        val byHref = cals.values.filter { it.source == Source.Google }.associateBy { it.href }
        val keep = feeds.map { it.href }.toSet()
        for (f in feeds) {
            val old = byHref[f.href]
            if (old == null) { val id = nextId++; cals[id] = CalendarInfo(id, f.href, f.name, f.colorHex, Source.Google, editable = false, visible = true, syncToken = null) }
            else cals[old.id] = old.copy(name = f.name, colorHex = f.colorHex)
        }
        for (old in byHref.values) if (old.href !in keep) { cals.remove(old.id); objs.values.removeAll { it.calendarId == old.id } }
        ver++
        return calendars()
    }

    override fun calendars() = cals.values.toList()
    override fun setVisible(calendarId: Long, visible: Boolean) { cals[calendarId]?.let { cals[calendarId] = it.copy(visible = visible) }; ver++ }
    override fun setSyncToken(calendarId: Long, token: String?) { cals[calendarId]?.let { cals[calendarId] = it.copy(syncToken = token) } }
    override fun objects(calendarId: Long) = objs.values.filter { it.calendarId == calendarId }
    override fun allObjects() = objs.values.toList()
    override fun objectByHref(href: String) = objs[href]
    override fun upsertObjects(calendarId: Long, objects: List<RemoteObject>) {
        for (o in objects) objs[o.href] = StoredObject(o.href, calendarId, o.etag, o.ics)
        ver++
    }
    override fun deleteObjects(calendarId: Long, hrefs: List<String>) { for (h in hrefs) objs.remove(h); ver++ }
    override fun clearObjects(calendarId: Long) { objs.values.removeAll { it.calendarId == calendarId }; ver++ }
    override fun objectCount(calendarId: Long) = objs.values.count { it.calendarId == calendarId }
    override fun version() = ver
    override fun clearAll() { cals.clear(); objs.clear(); ver++ }
}

const val DEFAULT_COLOR = "#00679E"

/** Nextcloud builds this calendar from Contacts: it is shown, but never edited, and hidden until the owner turns it on. */
fun isContactBirthdays(href: String) = href.trimEnd('/').endsWith("/contact_birthdays", ignoreCase = true)
