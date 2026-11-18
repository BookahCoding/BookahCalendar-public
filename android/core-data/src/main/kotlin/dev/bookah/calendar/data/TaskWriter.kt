package dev.bookah.calendar.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

/** Why a task cannot be ticked in this app (the task page says what to do instead). */
enum class TaskReadOnlyReason {
    /** The list is shared read-only (or is not a Nextcloud task list). */
    ReadOnlyList,

    /** A repeating task: ticking it means moving it to its next date, which only BookahTasks does. */
    Repeating,

    /** A parent whose subtasks are not all done. */
    OpenSubtasks,

    /** Has an organiser or attendees: a change could make the server send e-mails to other people. */
    WithPeople,

    /** Something the line-by-line edit does not handle safely (several tasks in one file, a task beside an event, a broken file). */
    Unusual,

    /** Not a task at all. */
    NotATask,

    /** Already finished (for a tick) or already open again (for an undo). */
    Finished,
}

/** Can the app tick this task? Judged on the whole stored object, like [IcsEdit.editability] for events. */
sealed interface TaskEditability {
    data object Editable : TaskEditability
    data class ReadOnly(val reason: TaskReadOnlyReason) : TaskEditability

    companion object {
        /**
         * Only a PLAIN task is ticked here: one VTODO and nothing else, no RRULE/RDATE/EXDATE/RECURRENCE-ID, no people, in a writable list,
         * and (told by the caller, who sees the other tasks) no open subtasks. Whether it is finished already is a separate question ([isFinished]).
         */
        fun of(ics: String, listWritable: Boolean, hasOpenSubtasks: Boolean = false): TaskEditability {
            if (!listWritable) return ReadOnly(TaskReadOnlyReason.ReadOnlyList)
            // CRLF and bare LF in one file: the line edit would read two lines as one (and could lose one of them)
            if ("\r\n" in ics && BARE_LF.containsMatchIn(ics)) return ReadOnly(TaskReadOnlyReason.Unusual)
            val s = scan(ics) ?: return ReadOnly(TaskReadOnlyReason.Unusual)
            if (s.todos == 0) return ReadOnly(TaskReadOnlyReason.NotATask)
            if (s.names.any { it == "RRULE" || it == "RDATE" || it == "EXDATE" || it == "RECURRENCE-ID" }) return ReadOnly(TaskReadOnlyReason.Repeating)
            if (s.todos > 1 || s.others > 0) return ReadOnly(TaskReadOnlyReason.Unusual)
            if (s.names.any { it == "ORGANIZER" || it == "ATTENDEE" }) return ReadOnly(TaskReadOnlyReason.WithPeople)
            if (hasOpenSubtasks) return ReadOnly(TaskReadOnlyReason.OpenSubtasks)
            // last safety net: a trial tick and undo must work on this text and change nothing but the tick's own lines
            val trial = runCatching {
                val done = TaskDone.complete(ics, Instant.EPOCH)
                done to TaskDone.reopen(done, Instant.EPOCH, TaskDone.before(ics))
            }.getOrNull() ?: return ReadOnly(TaskReadOnlyReason.Unusual)
            if (!onlyTickLinesDiffer(ics, trial.first) || !onlyTickLinesDiffer(ics, trial.second)) return ReadOnly(TaskReadOnlyReason.Unusual)
            return Editable
        }

        private val BARE_LF = Regex("(?<!\r)\n")
        private val TICK_LINES = setOf("STATUS", "COMPLETED", "PERCENT-COMPLETE", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE")

        /** True when [before] and [after] have the same logical lines, folds included, once the tick's own lines are left out of both. */
        internal fun onlyTickLinesDiffer(before: String, after: String): Boolean {
            fun rest(ics: String) = IcsEdit.logicalLines(ics).filter { IcsEdit.name(it) !in TICK_LINES }
            return rest(before) == rest(after)
        }

        /** Finished as BookahTasks reads it: STATUS COMPLETED or CANCELLED, or a COMPLETED time (PERCENT-COMPLETE:100 alone stays open). */
        fun isFinished(ics: String): Boolean {
            val status = runCatching { TaskPatch.readProperty(ics, "STATUS") }.getOrNull()?.trim()?.uppercase()
            val completed = runCatching { TaskPatch.readProperty(ics, "COMPLETED") }.getOrNull()
            return status == "COMPLETED" || status == "CANCELLED" || !completed.isNullOrBlank()
        }

        private class Scan(val todos: Int, val others: Int, val names: Set<String>)

        /** The components of the file and the property names of its VTODOs (their own lines, not those of a reminder inside). Null = broken nesting. */
        private fun scan(ics: String): Scan? {
            val stack = ArrayList<String>()
            var todos = 0
            var others = 0
            val names = HashSet<String>()
            for (l in IcsEdit.logicalLines(ics)) {
                when (IcsEdit.name(l)) {
                    "BEGIN" -> {
                        val kind = IcsEdit.value(l).trim().uppercase()
                        if (stack.size == 1 && stack[0] == "VCALENDAR") when (kind) {
                            "VTODO" -> todos++
                            "VTIMEZONE" -> {}
                            else -> others++
                        }
                        stack.add(kind)
                    }
                    "END" -> {
                        val kind = IcsEdit.value(l).trim().uppercase()
                        if (stack.lastOrNull() != kind) return null
                        stack.removeAt(stack.size - 1)
                    }
                    else -> if (stack.size == 2 && stack[1] == "VTODO") names.add(IcsEdit.name(l))
                }
            }
            if (stack.isNotEmpty()) return null
            return Scan(todos, others, names)
        }
    }
}

/**
 * True when the request surely never left the phone (no connection, unknown host, connect timeout, TLS handshake): then nothing can have
 * changed on the server. Anything else (a read timeout, a cut connection) may come after the server got the request.
 */
fun IOException.beforeRequestSent(): Boolean = when (this) {
    is java.net.ConnectException, is java.net.UnknownHostException, is java.net.NoRouteToHostException, is javax.net.ssl.SSLHandshakeException -> true
    is java.net.SocketTimeoutException -> message?.contains("connect", ignoreCase = true) == true
    else -> false
}

/** The UIDs of tasks that have at least one open subtask (RELATED-TO parent). */
fun openParentUids(tasks: List<TaskItem>): Set<String> = tasks.filter { !it.done }.mapNotNull { it.parentUid }.toSet()

/** What happened to a tick or an undo. */
sealed interface TaskWriteResult {
    /**
     * Written on the server and in the phone's copy. [etag] is the server's new one (an undo must name it); [before] is what a tick overwrote
     * (STATUS and PERCENT-COMPLETE), which the undo puts back.
     */
    data class Saved(val href: String, val etag: String, val before: TaskDone.BeforeTick = TaskDone.BeforeTick.Open) : TaskWriteResult

    /**
     * The request was sent but its answer was lost (or the new version could not be read back): the change may or may not be on the server.
     * The phone's copy is unchanged; a refresh brings the truth (and the server's ETag).
     */
    data object MaybeSaved : TaskWriteResult

    /** The task was changed elsewhere since it was read. Nothing was written; the phone's copy now holds [theirs], the version on the server. */
    data class Conflict(val theirs: RemoteObject) : TaskWriteResult

    /** The task no longer exists on the server (it is gone from the phone's copy too). Nothing was written. */
    data object Gone : TaskWriteResult

    /** Not allowed here (see [TaskReadOnlyReason]). Nothing was sent. */
    data class Refused(val reason: TaskReadOnlyReason) : TaskWriteResult

    /** The server could not be reached or refused. Nothing was written and the phone's copy is unchanged. */
    data class Failed(val message: String, val offline: Boolean) : TaskWriteResult
}

/**
 * Ticks a task off on the server, and takes a tick back (undo), keeping the phone's copy in step. Online only: there is no outbox. The same
 * rules as [EventWriter]: If-Match with the ETag we read, the ETag kept is always the server's own, 412 means somebody else changed it
 * (nothing is overwritten; the phone takes their version), 404 means it is gone. The caller holds the sync lock and makes one write at a time.
 */
class TaskWriter(
    private val store: CalendarStore,
    private val api: CalDavApi,
    private val clock: () -> Instant = { Instant.now() },
) {
    /** Marks the task in the object at [href] done, using the version in the phone's copy now. */
    suspend fun complete(href: String): TaskWriteResult = guard {
        val current = store.objectByHref(href) ?: return@guard TaskWriteResult.Gone
        refusal(current, checkSubtasks = true)?.let { return@guard it }
        if (TaskEditability.isFinished(current.ics)) return@guard TaskWriteResult.Refused(TaskReadOnlyReason.Finished)
        save(current, TaskDone.complete(current.ics, clock()), ifMatch = current.etag, before = TaskDone.before(current.ics))
    }

    /**
     * Undo of a tick: opens the task again, but only if it is still exactly the version our tick wrote ([expectedEtag], from [TaskWriteResult.Saved]).
     * If anything changed since (here after a refresh, or on the server), nothing is written and the answer is a [TaskWriteResult.Conflict].
     * [before] (from that [TaskWriteResult.Saved]) puts STATUS and PERCENT-COMPLETE back as they were.
     */
    suspend fun reopen(href: String, expectedEtag: String, before: TaskDone.BeforeTick): TaskWriteResult = guard {
        val current = store.objectByHref(href) ?: return@guard TaskWriteResult.Gone
        if (current.etag != expectedEtag) return@guard conflictOrGone(current)
        refusal(current, checkSubtasks = false)?.let { return@guard it }
        if (!TaskEditability.isFinished(current.ics)) return@guard TaskWriteResult.Refused(TaskReadOnlyReason.Finished)
        save(current, TaskDone.reopen(current.ics, clock(), before), ifMatch = expectedEtag)
    }

    private fun refusal(obj: StoredObject, checkSubtasks: Boolean): TaskWriteResult.Refused? {
        val calendar = store.calendars().firstOrNull { it.id == obj.calendarId }
        // not EventWriter's check: that one refuses every task-only list (events cannot go there), which is exactly where tasks live
        val writable = calendar != null && calendar.source == Source.Nextcloud && calendar.editable && calendar.kind.holdsTasks
        val subtasks = checkSubtasks && writable && hasOpenSubtasks(obj)
        return (TaskEditability.of(obj.ics, writable, subtasks) as? TaskEditability.ReadOnly)?.let { TaskWriteResult.Refused(it.reason) }
    }

    /** Does any open task in the phone's copy (shown lists or not) name this one as its parent? */
    private fun hasOpenSubtasks(obj: StoredObject): Boolean {
        val uid = TaskReader.parse(obj.calendarId, obj.href, obj.ics, java.time.ZoneOffset.UTC).firstOrNull()?.uid ?: return false
        val taskLists = store.calendars().filter { it.source == Source.Nextcloud && it.kind.holdsTasks }.map { it.id }.toSet()
        return store.allObjects().asSequence()
            // a quick text filter first, on UNFOLDED text: a long UID in RELATED-TO is split over two physical lines
            .filter { it.calendarId in taskLists && it.href != obj.href && it.ics.contains("RELATED-TO", ignoreCase = true) && IcsEdit.unfold(it.ics).contains(uid) }
            .flatMap { TaskReader.parse(it.calendarId, it.href, it.ics, java.time.ZoneOffset.UTC) }
            .any { !it.done && it.parentUid == uid }
    }

    /**
     * The write and the phone's copy go together: leaving the app half-way (the view model's scope cancelled) must not leave a written task
     * with an old ETag on the phone, so this part is not cancellable.
     */
    private suspend fun save(current: StoredObject, ics: String, ifMatch: String, before: TaskDone.BeforeTick = TaskDone.BeforeTick.Open): TaskWriteResult =
        withContext(NonCancellable) {
            val put = try {
                api.put(current.href, ics, ifMatch)
            } catch (e: PreconditionFailedException) {
                return@withContext conflictOrGone(current)
            } catch (e: CalDavException) {
                if (e.code == 404) { store.deleteObjects(current.calendarId, listOf(current.href)); return@withContext TaskWriteResult.Gone }
                throw e
            } catch (e: IOException) {
                // never "nothing was changed" once the request may have reached the server
                if (e.beforeRequestSent()) throw e else return@withContext TaskWriteResult.MaybeSaved
            }
            // the ETag must be the server's. If it did not send one, read the object back (it may also have changed the text).
            val stored = if (put.etag != null) RemoteObject(current.href, put.etag, ics)
            else (try { api.get(current.href) } catch (e: IOException) { null }) ?: return@withContext TaskWriteResult.MaybeSaved
            store.upsertObjects(current.calendarId, listOf(stored))
            TaskWriteResult.Saved(current.href, stored.etag, before)
        }

    /** Somebody else changed it: the phone takes the server's version now (so the screens show it), or forgets it when it is gone. */
    private suspend fun conflictOrGone(current: StoredObject): TaskWriteResult {
        val theirs = api.get(current.href)
        if (theirs == null) { store.deleteObjects(current.calendarId, listOf(current.href)); return TaskWriteResult.Gone }
        store.upsertObjects(current.calendarId, listOf(theirs))
        return TaskWriteResult.Conflict(theirs)
    }

    private suspend fun guard(block: suspend () -> TaskWriteResult): TaskWriteResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        TaskWriteResult.Failed("Could not reach the server. Nothing was changed.", offline = true)
    } catch (e: CalDavException) {
        TaskWriteResult.Failed(e.message ?: "The server refused the change. Nothing was changed.", offline = false)
    }
}
