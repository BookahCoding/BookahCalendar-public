package dev.bookah.calendar.data

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Brings the phone's copy of the Nextcloud calendars up to date. A failed run never removes anything that is already cached:
 * if the phone is off the tailnet the old events stay visible.
 */
class SyncEngine(private val store: CalendarStore, private val chunk: Int = 50) {

    suspend fun sync(api: CalDavApi): SyncReport {
        var changed = 0
        var removed = 0
        var full = 0
        val calendars: List<CalendarInfo>
        try {
            calendars = store.replaceNextcloudCalendars(api.listCalendars()).filter { it.source == Source.Nextcloud }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SyncReport(store.calendars().size, 0, 0, 0, offline = true, error = e.message)
        } catch (e: CalDavException) {
            return SyncReport(store.calendars().size, 0, 0, 0, offline = false, error = e.message, errorCode = e.code)
        }
        try {
            for (cal in calendars) {
                var token = cal.syncToken
                var attempt = 0
                while (true) {
                    try {
                        val delta = api.syncCollection(cal.href, token)
                        // fetch before touching the store, so a broken download leaves the old state in place
                        val fetched = ArrayList<RemoteObject>()
                        for (part in delta.changed.chunked(chunk)) fetched += api.multiget(cal.href, part.map { it.href })
                        if (token == null) store.clearObjects(cal.id)           // a full fetch replaces everything
                        if (delta.removed.isNotEmpty()) store.deleteObjects(cal.id, delta.removed)
                        if (fetched.isNotEmpty()) store.upsertObjects(cal.id, fetched)
                        store.setSyncToken(cal.id, delta.newToken)
                        changed += fetched.size
                        removed += delta.removed.size
                        break
                    } catch (e: SyncTokenInvalidException) {
                        if (attempt++ > 0) throw CalDavException(0, "The server keeps refusing the sync token.")
                        full++
                        store.setSyncToken(cal.id, null)
                        token = null
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return SyncReport(calendars.size, changed, removed, full, offline = true, error = e.message)
        } catch (e: CalDavException) {
            return SyncReport(calendars.size, changed, removed, full, offline = false, error = e.message, errorCode = e.code)
        }
        return SyncReport(calendars.size, changed, removed, full, offline = false)
    }
}
