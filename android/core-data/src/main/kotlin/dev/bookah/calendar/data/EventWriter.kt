package dev.bookah.calendar.data

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.UUID

/** What happened to a save or delete. */
sealed interface WriteResult {
    /** Done on the server and in the phone's copy. */
    data class Saved(val href: String) : WriteResult

    /** The event was changed on the server after we read it. [theirs] is the version there now. Nothing was written. */
    data class Conflict(val theirs: RemoteObject) : WriteResult

    /** The event no longer exists on the server. Nothing was written. */
    data object Gone : WriteResult

    /** Not allowed (read-only calendar, repeating event, event with people, bad times). Nothing was written. */
    data class Refused(val reason: String) : WriteResult

    /** The server could not be reached or answered with an error. Nothing was written (the form keeps what was typed). */
    data class Failed(val message: String, val offline: Boolean) : WriteResult
}

/**
 * Creates, changes and deletes events on the server and keeps the phone's copy in step. The rules (docs/phase-3-design.md 2 and 2b):
 * a new event is refused if its address exists; a change or delete is refused by the server if the event changed since we read it
 * (then nothing is written and the caller decides); only plain events in writable Nextcloud calendars are touched; the ETag is the
 * server's own, never guessed. The caller holds the sync lock, so a sync never runs in the middle of a write.
 */
class EventWriter(
    private val store: CalendarStore,
    private val api: CalDavApi,
    private val newUid: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun create(calendarId: Long, draft: EventDraft): WriteResult {
        val calendar = store.calendars().firstOrNull { it.id == calendarId }
        // a task-only list would refuse an event anyway: never even try (the form already leaves such lists out)
        if (calendar == null || calendar.source != Source.Nextcloud || !calendar.editable || !calendar.kind.holdsEvents) return WriteResult.Refused("This calendar cannot be changed.")
        IcsEdit.validate(draft.whenAt)?.let { return WriteResult.Refused(it) }
        val uid = newUid()
        val href = calendar.href.trimEnd('/') + "/" + uid + ".ics"
        val ics = IcsEdit.newEvent(uid, draft, clock())
        return guard { save(calendar.id, href, ics, ifMatch = null) }
    }

    /** Changes the event at [href] using the version in the phone's copy (read now, not when the page was opened). */
    suspend fun update(href: String, edit: EventEdit): WriteResult {
        val current = store.objectByHref(href) ?: return WriteResult.Gone
        return guard { change(current, edit) }
    }

    /** After a [WriteResult.Conflict]: apply the same changes on top of the version that is on the server now. */
    suspend fun updateOnTheirs(href: String, edit: EventEdit): WriteResult = guard {
        val local = store.objectByHref(href)
        val theirs = api.get(href)
        if (theirs == null) { if (local != null) store.deleteObjects(local.calendarId, listOf(href)); return@guard WriteResult.Gone }
        change(StoredObject(href, local?.calendarId ?: return@guard WriteResult.Gone, theirs.etag, theirs.ics), edit)
    }

    /** After a [WriteResult.Conflict]: drop the user's edit and keep what is on the server. */
    suspend fun keepTheirs(href: String): WriteResult = guard {
        val local = store.objectByHref(href) ?: return@guard WriteResult.Gone
        val theirs = api.get(href)
        if (theirs == null) { store.deleteObjects(local.calendarId, listOf(href)); WriteResult.Gone }
        else { store.upsertObjects(local.calendarId, listOf(theirs)); WriteResult.Saved(href) }
    }

    suspend fun delete(href: String): WriteResult {
        val current = store.objectByHref(href) ?: return WriteResult.Gone
        return guard { remove(current, current.etag) }
    }

    /** After a [WriteResult.Conflict] on a delete: delete the event as it is on the server now. */
    suspend fun deleteOnTheirs(href: String): WriteResult = guard {
        val local = store.objectByHref(href) ?: return@guard WriteResult.Gone
        val theirs = api.get(href)
        if (theirs == null) { store.deleteObjects(local.calendarId, listOf(href)); return@guard WriteResult.Gone }
        remove(StoredObject(href, local.calendarId, theirs.etag, theirs.ics), theirs.etag)
    }

    // ------------------------------------------------------------------------------- repeating events (docs/phase-4-design.md)

    /** Changes [scope] of a repeating event. [onTheirs] = after a conflict: do the same on top of the version that is on the server now. */
    suspend fun updateOccurrence(href: String, occ: Occurrence, scope: Scope, edit: EventEdit, onTheirs: Boolean = false): WriteResult =
        runSeries(href, onTheirs) { ics -> SeriesEdit.edit(ics, occ, edit, scope, clock(), newUid()) }

    /** Deletes [scope] of a repeating event, for the occurrence whose original slot starts at [originalStartMs]. */
    suspend fun deleteOccurrence(href: String, originalStartMs: Long, scope: Scope, onTheirs: Boolean = false): WriteResult =
        runSeries(href, onTheirs) { ics -> SeriesEdit.delete(ics, originalStartMs, scope, clock()) }

    /**
     * Deletes the whole stored object, whatever kind of event it holds (a plain event, a whole series, an event with other people). The caller has
     * already asked the user (with a strong warning for events with other people). Still refused for a read-only calendar or a non-event.
     */
    suspend fun deleteWholeObject(href: String, onTheirs: Boolean = false): WriteResult = guard {
        val local = store.objectByHref(href) ?: return@guard WriteResult.Gone
        val current = if (onTheirs) {
            val theirs = api.get(href)
            if (theirs == null) { store.deleteObjects(local.calendarId, listOf(href)); return@guard WriteResult.Gone }
            StoredObject(href, local.calendarId, theirs.etag, theirs.ics)
        } else local
        calendarRefusal(current)?.let { return@guard it }
        if (SeriesEdit.parseDoc(current.ics) == null) return@guard WriteResult.Refused("This is not an event.")
        remove(current, current.etag, checked = true)
    }

    private suspend fun runSeries(href: String, onTheirs: Boolean, op: (String) -> SeriesChange?): WriteResult = guard {
        val local = store.objectByHref(href) ?: return@guard WriteResult.Gone
        val current = if (onTheirs) {
            val theirs = api.get(href)
            if (theirs == null) { store.deleteObjects(local.calendarId, listOf(href)); return@guard WriteResult.Gone }
            StoredObject(href, local.calendarId, theirs.etag, theirs.ics)
        } else local
        calendarRefusal(current)?.let { return@guard it }
        if (SeriesEdit.editability(current.ics) != Editability.EditableSeries) return@guard WriteResult.Refused("This repeating event cannot be changed here.")
        val change = try { op(current.ics) } catch (e: IllegalArgumentException) { return@guard WriteResult.Refused(e.message ?: "This change is not possible.") }
            ?: return@guard WriteResult.Refused("This repeating event cannot be changed here.")
        when (change) {
            is SeriesChange.Replace -> save(current.calendarId, current.href, change.ics, ifMatch = current.etag)
            SeriesChange.DeleteObject -> remove(current, current.etag, checked = true)
            is SeriesChange.Split -> split(current, change)
        }
    }

    /**
     * "This and following": two writes. The later part is created first (a new object); only then is the earlier part shortened. If the second
     * write fails, the new object is deleted again so nothing is doubled; if even that fails the user is told.
     */
    private suspend fun split(current: StoredObject, change: SeriesChange.Split): WriteResult {
        val calendar = store.calendars().first { it.id == current.calendarId }
        val newHref = calendar.href.trimEnd('/') + "/" + change.newUid + ".ics"
        val created = try {
            api.put(newHref, change.newIcs, ifMatch = null)
        } catch (e: PreconditionFailedException) {
            return WriteResult.Failed("An event with this address already exists. Try again.", offline = false)
        }
        val newStored = if (created.etag != null) RemoteObject(newHref, created.etag, change.newIcs) else api.get(newHref)
            ?: return WriteResult.Failed("The later part could not be confirmed. Refresh to see it.", offline = false)
        val second = try {
            save(current.calendarId, current.href, change.oldIcs, ifMatch = current.etag)
        } catch (e: Exception) {
            undoCreated(newStored)
            throw e
        }
        return if (second is WriteResult.Saved) { store.upsertObjects(current.calendarId, listOf(newStored)); second }
        else { undoCreated(newStored); second }
    }

    private suspend fun undoCreated(created: RemoteObject) {
        try { api.delete(created.href, created.etag) } catch (e: Exception) { /* reported by the caller's failure; the duplicate is visible after the next refresh */ }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private suspend fun change(current: StoredObject, edit: EventEdit): WriteResult {
        refusal(current)?.let { return it }
        edit.whenAt?.let { IcsEdit.validate(it)?.let { problem -> return WriteResult.Refused(problem) } }
        val ics = IcsEdit.applyEdit(current.ics, edit, clock())
        return save(current.calendarId, current.href, ics, ifMatch = current.etag)
    }

    /** [checked] = the caller has already checked what may be deleted (series, events with people); only plain events are checked here. */
    private suspend fun remove(current: StoredObject, etag: String, checked: Boolean = false): WriteResult {
        if (!checked) refusal(current)?.let { return it }
        return try {
            api.delete(current.href, etag)
            store.deleteObjects(current.calendarId, listOf(current.href))
            WriteResult.Saved(current.href)
        } catch (e: PreconditionFailedException) {
            conflictOrGone(current)
        } catch (e: CalDavException) {
            if (e.code == 404) { store.deleteObjects(current.calendarId, listOf(current.href)); WriteResult.Saved(current.href) } else throw e   // already gone: same result
        }
    }

    private suspend fun save(calendarId: Long, href: String, ics: String, ifMatch: String?): WriteResult {
        val put = try {
            api.put(href, ics, ifMatch)
        } catch (e: PreconditionFailedException) {
            // a new event whose address is taken (ifMatch == null) is not a conflict of ours: report it as a failure
            if (ifMatch == null) return WriteResult.Failed("An event with this address already exists. Try again.", offline = false)
            return conflictOrGone(StoredObject(href, calendarId, ifMatch, ics))
        } catch (e: CalDavException) {
            if (e.code == 404 && ifMatch != null) { store.deleteObjects(calendarId, listOf(href)); return WriteResult.Gone }   // deleted elsewhere
            throw e
        }
        // the ETag must be the server's. If it did not send one, read the object back (it may also have changed the text).
        val stored = if (put.etag != null) RemoteObject(href, put.etag, ics) else api.get(href)
            ?: return WriteResult.Failed("Saved, but the server did not confirm it. Refresh to see it.", offline = false)
        store.upsertObjects(calendarId, listOf(stored))
        return WriteResult.Saved(href)
    }

    private suspend fun conflictOrGone(current: StoredObject): WriteResult {
        val theirs = api.get(current.href)
        if (theirs == null) { store.deleteObjects(current.calendarId, listOf(current.href)); return WriteResult.Gone }
        return WriteResult.Conflict(theirs)
    }

    /** Null when the calendar of this object may be written to at all (a writable Nextcloud calendar). */
    private fun calendarRefusal(obj: StoredObject): WriteResult.Refused? {
        val calendar = store.calendars().firstOrNull { it.id == obj.calendarId }
        // a task-only list never gets an event write (its items are tasks; their tick goes through TaskWriter, everything else is BookahTasks')
        return if (calendar == null || calendar.source != Source.Nextcloud || !calendar.editable || !calendar.kind.holdsEvents) WriteResult.Refused("This calendar cannot be changed.") else null
    }

    /** Null when this stored object may be changed or deleted by the app. */
    private fun refusal(obj: StoredObject): WriteResult.Refused? {
        calendarRefusal(obj)?.let { return it }
        return when (val e = IcsEdit.editability(obj.ics)) {
            Editability.Editable -> null
            Editability.EditableSeries -> WriteResult.Refused("Choose which occurrences to change.")      // never returned by the plain check; series go through the scope methods
            is Editability.ReadOnly -> WriteResult.Refused(
                when (e.reason) {
                    ReadOnlyReason.Repeating -> "Repeating events cannot be changed yet."
                    ReadOnlyReason.Invitation -> "Events with other people cannot be changed here."
                    ReadOnlyReason.NotAnEvent -> "This is not an event."
                },
            )
        }
    }

    private suspend fun guard(block: suspend () -> WriteResult): WriteResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        WriteResult.Failed("Could not reach the server. Nothing was saved.", offline = true)
    } catch (e: PreconditionFailedException) {
        WriteResult.Failed("The event was changed on the server. Nothing was saved.", offline = false)
    } catch (e: CalDavException) {
        WriteResult.Failed(e.message ?: "The server refused the change. Nothing was saved.", offline = false)
    }
}
