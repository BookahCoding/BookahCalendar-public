package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.ListKind
import dev.bookah.calendar.data.RemoteObject
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import dev.bookah.calendar.data.TaskEditability
import dev.bookah.calendar.data.TaskItem
import dev.bookah.calendar.data.TaskReadOnlyReason
import dev.bookah.calendar.data.TaskWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of ticking in the view model: which rows get a working checkbox, what the bar says, one write at a time. */
class TaskTicksTest {
    private fun cal(id: Long, editable: Boolean = true, kind: ListKind = ListKind.Tasks, source: Source = Source.Nextcloud) =
        CalendarInfo(id, "/cal/$id/", "List $id", "#3E8E41", source, editable, visible = true, syncToken = null, kind = kind)

    private fun task(uid: String, cal: Long = 1, parent: String? = null, done: Boolean = false) =
        TaskItem(cal, uid, "/cal/$cal/$uid.ics", "Generated $uid", null, null, false, null, false, done, 0, parent, null)

    private fun obj(t: TaskItem, vararg lines: String) = StoredObject(
        t.objectHref, t.calendarId, "e",
        "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:${t.uid}\r\nSUMMARY:x\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n",
    )

    @Test fun eachShownTaskGetsItsEditability() {
        val plain = task("plain")
        val repeating = task("rep")
        val parent = task("parent")
        val child = task("child", parent = "parent")
        val shared = task("shared", cal = 2)
        val tasks = listOf(plain, repeating, parent, child, shared)
        val objects = listOf(obj(plain), obj(repeating, "RRULE:FREQ=DAILY"), obj(parent), obj(child, "RELATED-TO:parent"), obj(shared)).associateBy { it.href }
        val map = taskEditabilities(tasks, objects, listOf(cal(1), cal(2, editable = false)).associateBy { it.id })
        assertEquals(TaskEditability.Editable, map[plain.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Repeating), map[repeating.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.OpenSubtasks), map[parent.key])
        assertEquals(TaskEditability.Editable, map[child.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList), map[shared.key])
    }

    @Test fun aTaskWhoseObjectOrListIsMissingIsNotTickable() {
        val t = task("lost")
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList), taskEditabilities(listOf(t), emptyMap(), emptyMap())[t.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList), taskEditabilities(listOf(t), mapOf(t.objectHref to obj(t)), emptyMap())[t.key])
    }

    @Test fun finishedTasksAreLeftOut() {
        val t = task("done", done = true)
        assertTrue(taskEditabilities(listOf(t), mapOf(t.objectHref to obj(t, "STATUS:COMPLETED")), mapOf(1L to cal(1))).isEmpty())
    }

    @Test fun whatTheBarSaysAfterAWrite() {
        assertNull(taskNote(TaskWriteResult.Saved("/a", "e1"), undo = false))
        assertEquals(TaskNote.ChangedElsewhere, taskNote(TaskWriteResult.Conflict(RemoteObject("/a", "e2", "")), undo = false))
        assertEquals(TaskNote.Gone, taskNote(TaskWriteResult.Gone, undo = false))
        assertEquals(TaskNote.Offline, taskNote(TaskWriteResult.Failed("x", offline = true), undo = false))
        assertEquals(TaskNote.Failed, taskNote(TaskWriteResult.Failed("x", offline = false), undo = false))
        assertEquals(TaskNote.Refused, taskNote(TaskWriteResult.Refused(TaskReadOnlyReason.Repeating), undo = false))
        // the request may have reached the server: never "nothing was changed"
        assertEquals(TaskNote.MaybeSaved, taskNote(TaskWriteResult.MaybeSaved, undo = false))
        assertEquals(TaskNote.MaybeSaved, taskNote(TaskWriteResult.MaybeSaved, undo = true))
        // "finished" means already done for a tick, already open again for an undo
        assertEquals(TaskNote.AlreadyDone, taskNote(TaskWriteResult.Refused(TaskReadOnlyReason.Finished), undo = false))
        assertEquals(TaskNote.AlreadyOpen, taskNote(TaskWriteResult.Refused(TaskReadOnlyReason.Finished), undo = true))
        // an undo refused for another reason says the undo was not done (not "cannot be ticked")
        assertEquals(TaskNote.UndoRefused, taskNote(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), undo = true))
    }

    @Test fun afterATickTheBarCarriesWhatTheUndoNeeds() {
        val before = dev.bookah.calendar.data.TaskDone.BeforeTick("IN-PROCESS", "40")
        val after = afterTaskWrite(TaskWriteResult.Saved("/a.ics", "e7", before), undo = false, href = "/a.ics", openPageHref = "/a.ics", id = 3)
        assertEquals(TaskBar.Done("/a.ics", "e7", before, 3), after.bar)
        assertTrue(after.closePage)                       // ticked from its page: the page closes
        assertFalse(after.refresh)
        assertFalse(afterTaskWrite(TaskWriteResult.Saved("/a.ics", "e7"), undo = false, href = "/a.ics", openPageHref = "/b.ics", id = 4).closePage)
    }

    @Test fun anUndoThatWorkedLeavesNoBar() {
        val after = afterTaskWrite(TaskWriteResult.Saved("/a.ics", "e8"), undo = true, href = "/a.ics", openPageHref = null, id = 5)
        assertNull(after.bar)
        assertFalse(after.refresh)
    }

    @Test fun aTaskGoneElsewhereClosesItsPage() {
        val after = afterTaskWrite(TaskWriteResult.Gone, undo = false, href = "/a.ics", openPageHref = "/a.ics", id = 6)
        assertTrue(after.closePage)
        assertEquals(TaskBar.Message(TaskNote.Gone, 6), after.bar)
    }

    @Test fun maybeSavedAsksForARefresh() {
        val after = afterTaskWrite(TaskWriteResult.MaybeSaved, undo = false, href = "/a.ics", openPageHref = null, id = 7)
        assertTrue(after.refresh)
        assertEquals(TaskBar.Message(TaskNote.MaybeSaved, 7), after.bar)
        assertFalse(afterTaskWrite(TaskWriteResult.Failed("x", offline = true), undo = false, href = "/a.ics", openPageHref = null, id = 8).refresh)
    }

    @Test fun oneWriteAtATime() {
        val gate = WriteGate()
        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())           // a second tap while the first is saving does nothing
        gate.leave()
        assertTrue(gate.tryEnter())
    }

    @Test fun eachBarHasItsOwnNumberSoTheSameMessageTwiceShowsTwice() {
        val a = TaskBar.Message(TaskNote.Offline, 1)
        val b = TaskBar.Message(TaskNote.Offline, 2)
        assertFalse(a == b)
    }
}
