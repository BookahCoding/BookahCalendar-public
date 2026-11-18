package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import dev.bookah.calendar.data.TaskEditability
import dev.bookah.calendar.data.TaskItem
import dev.bookah.calendar.data.TaskReadOnlyReason
import dev.bookah.calendar.data.TaskWriteResult
import dev.bookah.calendar.data.openParentUids
import java.time.LocalDate

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

/**
 * Whether each open task WITHOUT a date (the Tasks page) can be given a day here, by task key (see [dev.bookah.calendar.data.TaskMove.editability]).
 * Computed when the days are loaded, never per row while drawing.
 */
fun taskMovabilities(undated: List<TaskItem>, objects: Map<String, StoredObject>, calendars: Map<Long, CalendarInfo>): Map<String, TaskEditability> {
    val out = HashMap<String, TaskEditability>()
    for (t in undated) {
        if (t.done || t.start != null || t.due != null) continue
        val cal = calendars[t.calendarId]
        val writable = cal != null && cal.source == Source.Nextcloud && cal.editable && cal.kind.holdsTasks
        val obj = objects[t.objectHref]
        out[t.key] = when {
            !writable -> TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList)
            obj == null -> TaskEditability.ReadOnly(TaskReadOnlyReason.NotATask)
            else -> dev.bookah.calendar.data.TaskMove.editability(obj.ics, listWritable = true)
        }
    }
    return out
}

/** The days the app loads and shows: about six months back to thirteen ahead ([first] included, [end] excluded). */
data class LoadedWindow(val first: LocalDate, val end: LocalDate)

fun loadedWindow(today: LocalDate) = LoadedWindow(today.minusDays(180).withDayOfMonth(1), today.plusMonths(13).withDayOfMonth(1))

/** The days a task may be given from the calendar: today up to the last loaded day, so it always lands on a day the app shows. */
fun pickableDay(date: LocalDate, today: LocalDate): Boolean = !date.isBefore(today) && date.isBefore(loadedWindow(today).end)

/** The drop targets shown while a task is dragged on the Tasks page: today, tomorrow and the next five days. */
fun dropDays(today: LocalDate): List<LocalDate> = (0L until 7L).map { today.plusDays(it) }

/** A drop target on screen (root coordinates, pixels). */
data class DropBox(val date: LocalDate, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** The day under the finger when it is lifted, or null (dropped elsewhere: nothing is written). */
fun dayAt(boxes: List<DropBox>, x: Float, y: Float): LocalDate? = boxes.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }?.date

/**
 * One drag of a task on the Tasks page (root coordinates, pixels). Only the row that started it ([task]'s key) may move or end it, so a second
 * finger on another row changes nothing. [stripOnTop]: the drag started in the lower half, so the day strip is shown at the top, never under
 * the finger where it starts.
 */
data class DragSession(val task: TaskItem, val startX: Float, val startY: Float, val x: Float, val y: Float, val stripOnTop: Boolean) {
    /** How far the finger went from where the drag started. */
    val moved: Float get() = kotlin.math.hypot(x - startX, y - startY)

    /** The finger moved; moves reported by any other row are ignored. */
    fun moveBy(key: String, dx: Float, dy: Float): DragSession = if (key != task.key) this else copy(x = x + dx, y = y + dy)
}

/** A new drag, or null while another one is still running (a second finger never takes over). */
fun beginDrag(current: DragSession?, task: TaskItem, x: Float, y: Float, screenHeight: Float): DragSession? =
    if (current != null) null else DragSession(task, x, y, x, y, stripOnTop = y > screenHeight / 2)

fun ownsDrag(session: DragSession?, key: String): Boolean = session != null && session.task.key == key

/**
 * The day a lifted finger drops the task on, or null (nothing is written): only for the row that owns the drag, only when the strip was on
 * screen ([boxes] laid out), and only when the finger really moved (more than [slop]) since the drag started: a long press alone never moves a task.
 */
fun dropDay(session: DragSession?, key: String, boxes: List<DropBox>, slop: Float): LocalDate? {
    if (!ownsDrag(session, key) || boxes.isEmpty() || session!!.moved <= slop) return null
    return dayAt(boxes, session.x, session.y)
}

/** What a day picked for a task leads to. */
enum class MoveDecision { Go, OpenPage, PickAgain, Busy }

/**
 * [Go]: write it. [OpenPage]: the task cannot be given a day here (its page says why). [PickAgain]: the day is no longer allowed (the picker
 * stayed open over midnight). [Busy]: another task write still runs. The last two are said on the bar, never dropped silently.
 */
fun moveDecision(movability: TaskEditability?, date: LocalDate, today: LocalDate, busy: Boolean): MoveDecision = when {
    movability != TaskEditability.Editable -> MoveDecision.OpenPage
    !pickableDay(date, today) -> MoveDecision.PickAgain
    busy -> MoveDecision.Busy
    else -> MoveDecision.Go
}

/** What the bar at the bottom says after a tick, a move or an undo that did not simply work, or why a picked day was not used. */
enum class TaskNote { ChangedElsewhere, Gone, Offline, Failed, Refused, MaybeSaved, AlreadyDone, AlreadyOpen, AlreadyDated, MoveRefused, UndoRefused, PickAgain, Busy }

/** Null when the move (or its undo) worked. A refused move has its own words (the tick's ones speak of ticking); a refused undo says the undo was not done. */
fun moveNote(result: TaskWriteResult, undo: Boolean): TaskNote? = when (result) {
    is TaskWriteResult.Saved -> null
    TaskWriteResult.MaybeSaved -> TaskNote.MaybeSaved
    is TaskWriteResult.Conflict -> TaskNote.ChangedElsewhere
    TaskWriteResult.Gone -> TaskNote.Gone
    is TaskWriteResult.Refused -> when {
        undo -> TaskNote.UndoRefused
        result.reason == TaskReadOnlyReason.HasDates -> TaskNote.AlreadyDated
        else -> TaskNote.MoveRefused
    }
    is TaskWriteResult.Failed -> if (result.offline) TaskNote.Offline else TaskNote.Failed
}

/** After a move ([undo] false: "Task moved to <date> · Undo"; its page closes as after a tick) or after the undo of one (no bar when it worked). */
fun afterTaskMove(result: TaskWriteResult, undo: Boolean, href: String, openPageHref: String?, date: LocalDate, id: Long): AfterTaskWrite {
    val bar = when {
        result is TaskWriteResult.Saved && !undo -> TaskBar.Moved(result.href, result.etag, date, id)
        else -> moveNote(result, undo)?.let { TaskBar.Message(it, id) }
    }
    val closes = openPageHref == href && ((result is TaskWriteResult.Saved && !undo) || result == TaskWriteResult.Gone)
    return AfterTaskWrite(bar, closes, refresh = result == TaskWriteResult.MaybeSaved)
}

/** Null when the write worked (a tick then shows "Task done · Undo"; an undo shows nothing, the task is simply back). [undo]: the write was an undo. */
fun taskNote(result: TaskWriteResult, undo: Boolean): TaskNote? = when (result) {
    is TaskWriteResult.Saved -> null
    TaskWriteResult.MaybeSaved -> TaskNote.MaybeSaved
    is TaskWriteResult.Conflict -> TaskNote.ChangedElsewhere
    TaskWriteResult.Gone -> TaskNote.Gone
    is TaskWriteResult.Refused -> when {
        result.reason != TaskReadOnlyReason.Finished -> if (undo) TaskNote.UndoRefused else TaskNote.Refused
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

    /** "Task moved to <date> · Undo": [etag] is the server's ETag after the move, which the undo must still find; [date] the day it was given. */
    data class Moved(val href: String, val etag: String, val date: LocalDate, override val id: Long) : TaskBar
    data class Message(val note: TaskNote, override val id: Long) : TaskBar
}

/** One task write at a time: a second tap while one is saving does nothing. Used on the main thread only. */
class WriteGate {
    private var busy = false
    fun tryEnter(): Boolean = if (busy) false else { busy = true; true }
    fun leave() { busy = false }
}
