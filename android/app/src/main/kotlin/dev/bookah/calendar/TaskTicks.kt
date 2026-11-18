package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import dev.bookah.calendar.data.TaskEditability
import dev.bookah.calendar.data.TaskItem
import dev.bookah.calendar.data.TaskReadOnlyReason
import dev.bookah.calendar.data.TaskWriteResult
import dev.bookah.calendar.data.openParentUids

/**
 * Whether each OPEN task can be ticked here, by task key (finished ones are not shown and are left out). Computed when the days are loaded,
 * never per row while drawing. Open subtasks are seen among [tasks] (the shown lists); the writer checks again over every list before it writes.
 */
fun taskEditabilities(tasks: List<TaskItem>, objects: Map<String, StoredObject>, calendars: Map<Long, CalendarInfo>): Map<String, TaskEditability> {
    val openParents = openParentUids(tasks)
    val out = HashMap<String, TaskEditability>()
    for (t in tasks) {
        if (t.done) continue
        val cal = calendars[t.calendarId]
        val writable = cal != null && cal.source == Source.Nextcloud && cal.editable && cal.kind.holdsTasks
        val obj = objects[t.objectHref]
        out[t.key] = when {
            !writable -> TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList)
            obj == null -> TaskEditability.ReadOnly(TaskReadOnlyReason.NotATask)
            else -> TaskEditability.of(obj.ics, listWritable = true, hasOpenSubtasks = t.uid in openParents)
        }
    }
    return out
}

/** What the bar at the bottom says after a tick or an undo that did not simply work. */
enum class TaskNote { ChangedElsewhere, Gone, Offline, Failed, Refused, MaybeSaved, AlreadyDone, AlreadyOpen }

/** Null when the write worked (a tick then shows "Task done · Undo"; an undo shows nothing, the task is simply back). [undo]: the write was an undo. */
fun taskNote(result: TaskWriteResult, undo: Boolean): TaskNote? = when (result) {
    is TaskWriteResult.Saved -> null
    TaskWriteResult.MaybeSaved -> TaskNote.MaybeSaved
    is TaskWriteResult.Conflict -> TaskNote.ChangedElsewhere
    TaskWriteResult.Gone -> TaskNote.Gone
    is TaskWriteResult.Refused -> when {
        result.reason != TaskReadOnlyReason.Finished -> TaskNote.Refused
        undo -> TaskNote.AlreadyOpen
        else -> TaskNote.AlreadyDone
    }
    is TaskWriteResult.Failed -> if (result.offline) TaskNote.Offline else TaskNote.Failed
}

/** What the screen does after a tick or an undo: the bar to show, whether the open task page closes, whether to refresh from the server. */
data class AfterTaskWrite(val bar: TaskBar?, val closePage: Boolean, val refresh: Boolean)

/**
 * [href] is the task's object; [openPageHref] the object of the task page that is open (or null). A finished or vanished task closes its own
 * page; "may have been saved" asks for a refresh so the phone learns what the server has (and its ETag).
 */
fun afterTaskWrite(result: TaskWriteResult, undo: Boolean, href: String, openPageHref: String?, id: Long): AfterTaskWrite {
    val bar = when {
        result is TaskWriteResult.Saved && !undo -> TaskBar.Done(result.href, result.etag, result.before, id)
        else -> taskNote(result, undo)?.let { TaskBar.Message(it, id) }
    }
    val closes = openPageHref == href && ((result is TaskWriteResult.Saved && !undo) || result == TaskWriteResult.Gone)
    return AfterTaskWrite(bar, closes, refresh = result == TaskWriteResult.MaybeSaved)
}

/** The bar at the bottom of the screen. [id] differs for every bar, so the same message twice is shown (and timed) twice. */
sealed interface TaskBar {
    val id: Long

    /** "Task done · Undo": [etag] is the server's ETag after the tick, which the undo must still find; [before] is what the undo puts back. */
    data class Done(val href: String, val etag: String, val before: dev.bookah.calendar.data.TaskDone.BeforeTick, override val id: Long) : TaskBar
    data class Message(val note: TaskNote, override val id: Long) : TaskBar
}

/** One task write at a time: a second tap while one is saving does nothing. Used on the main thread only. */
class WriteGate {
    private var busy = false
    fun tryEnter(): Boolean = if (busy) false else { busy = true; true }
    fun leave() { busy = false }
}
