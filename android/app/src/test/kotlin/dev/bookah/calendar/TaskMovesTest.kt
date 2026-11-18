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
import java.time.LocalDate

/** The pure parts of giving an undated task a day: which rows may be moved, what the bar says, the drop targets and the pickable days. */
class TaskMovesTest {
    private val today = LocalDate.of(2026, 11, 18)

    private fun cal(id: Long, editable: Boolean = true) =
        CalendarInfo(id, "/cal/$id/", "List $id", "#3E8E41", Source.Nextcloud, editable, visible = true, syncToken = null, kind = ListKind.Tasks)

    private fun task(uid: String, cal: Long = 1, due: Long? = null) =
        TaskItem(cal, uid, "/cal/$cal/$uid.ics", "Generated $uid", null, null, false, due, due != null, false, 0, null, null)

    private fun obj(t: TaskItem, vararg lines: String) = StoredObject(
        t.objectHref, t.calendarId, "e",
        "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:${t.uid}\r\nSUMMARY:x\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n",
    )

    @Test fun eachUndatedTaskGetsItsMovability() {
        val plain = task("plain")
        val parked = task("parked")
        val repeating = task("rep")
        val shared = task("shared", cal = 2)
        val brokenStart = task("broken")
        val dated = task("dated", due = 0L)
        val tasks = listOf(plain, parked, repeating, shared, brokenStart, dated)
        val objects = listOf(obj(plain), obj(parked, "CATEGORIES:someday"), obj(repeating, "RRULE:FREQ=DAILY"), obj(shared), obj(brokenStart, "DTSTART:garbage"),
            obj(dated, "DUE;VALUE=DATE:19700101")).associateBy { it.href }
        val map = taskMovabilities(tasks, objects, listOf(cal(1), cal(2, editable = false)).associateBy { it.id })
        assertEquals(TaskEditability.Editable, map[plain.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Parked), map[parked.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.Repeating), map[repeating.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.ReadOnlyList), map[shared.key])
        assertEquals(TaskEditability.ReadOnly(TaskReadOnlyReason.HasDates), map[brokenStart.key])
        assertNull(map[dated.key])                                     // a dated task is not on the Tasks page
    }

    @Test fun afterAMoveTheBarCarriesWhatTheUndoNeedsAndThePageCloses() {
        val day = today.plusDays(2)
        val after = afterTaskMove(TaskWriteResult.Saved("/a.ics", "e7"), undo = false, href = "/a.ics", openPageHref = "/a.ics", date = day, id = 3)
        assertEquals(TaskBar.Moved("/a.ics", "e7", day, 3), after.bar)
        assertTrue(after.closePage)
        assertFalse(after.refresh)
        assertFalse(afterTaskMove(TaskWriteResult.Saved("/a.ics", "e7"), false, "/a.ics", "/b.ics", day, 4).closePage)
    }

    @Test fun anUndoOfAMoveThatWorkedLeavesNoBar() {
        val after = afterTaskMove(TaskWriteResult.Saved("/a.ics", "e8"), undo = true, href = "/a.ics", openPageHref = null, date = today, id = 5)
        assertNull(after.bar)
        assertFalse(after.closePage)
    }

    @Test fun whatTheBarSaysAfterAMoveThatDidNotWork() {
        assertEquals(TaskNote.ChangedElsewhere, moveNote(TaskWriteResult.Conflict(RemoteObject("/a", "e2", "")), undo = false))
        assertEquals(TaskNote.Gone, moveNote(TaskWriteResult.Gone, undo = false))
        assertEquals(TaskNote.Offline, moveNote(TaskWriteResult.Failed("x", offline = true), undo = false))
        assertEquals(TaskNote.Failed, moveNote(TaskWriteResult.Failed("x", offline = false), undo = false))
        assertEquals(TaskNote.MaybeSaved, moveNote(TaskWriteResult.MaybeSaved, undo = false))
        assertEquals(TaskNote.AlreadyDated, moveNote(TaskWriteResult.Refused(TaskReadOnlyReason.HasDates), undo = false))
        assertEquals(TaskNote.MoveRefused, moveNote(TaskWriteResult.Refused(TaskReadOnlyReason.Parked), undo = false))
        assertEquals(TaskNote.MoveRefused, moveNote(TaskWriteResult.Refused(TaskReadOnlyReason.Repeating), undo = false))
        // a lost answer asks for a refresh; a gone task closes its page
        assertTrue(afterTaskMove(TaskWriteResult.MaybeSaved, false, "/a.ics", null, today, 6).refresh)
        assertTrue(afterTaskMove(TaskWriteResult.Gone, false, "/a.ics", "/a.ics", today, 7).closePage)
    }

    @Test fun theDropTargetsAreTodayAndTheNextSixDays() {
        val days = dropDays(today)
        assertEquals(7, days.size)
        assertEquals(today, days.first())
        assertEquals(today.plusDays(1), days[1])
        assertEquals(today.plusDays(6), days.last())
    }

    @Test fun theDayUnderTheFingerIsFound() {
        val boxes = listOf(DropBox(today, 0f, 100f, 50f, 150f), DropBox(today.plusDays(1), 50f, 100f, 100f, 150f))
        assertEquals(today, dayAt(boxes, 10f, 120f))
        assertEquals(today.plusDays(1), dayAt(boxes, 50f, 149f))     // left edge belongs to the box, right edge to the next
        assertNull(dayAt(boxes, 10f, 99f))                            // above the strip: dropped nowhere, nothing is written
        assertNull(dayAt(boxes, 100f, 120f))
        assertNull(dayAt(emptyList(), 10f, 120f))
    }

    private val strip = listOf(DropBox(today, 0f, 1000f, 500f, 1100f), DropBox(today.plusDays(1), 500f, 1000f, 1000f, 1100f))

    @Test fun aLongPressWithoutMovementDropsNothingEvenOverTheStrip() {
        // the row was under where the strip appears: the finger is over "today" at once, but it never moved
        val s = beginDrag(null, task("a"), x = 100f, y = 1050f, screenHeight = 2000f)!!
        assertNull(dropDay(s, task("a").key, strip, slop = 20f))
        // a few pixels of jitter are still no drag
        assertNull(dropDay(s.moveBy(task("a").key, 5f, 5f), task("a").key, strip, slop = 20f))
    }

    @Test fun aRealDragOntoAVisibleStripDrops() {
        val s = beginDrag(null, task("a"), x = 100f, y = 300f, screenHeight = 2000f)!!.moveBy(task("a").key, 0f, 750f)
        assertEquals(today, dropDay(s, task("a").key, strip, slop = 20f))
        // the strip was never laid out (no boxes): nothing is dropped
        assertNull(dropDay(s, task("a").key, emptyList(), slop = 20f))
    }

    @Test fun theStripGoesToTheTopWhenTheDragStartsInTheLowerHalf() {
        assertFalse(beginDrag(null, task("a"), 100f, 900f, screenHeight = 2000f)!!.stripOnTop)
        assertTrue(beginDrag(null, task("a"), 100f, 1100f, screenHeight = 2000f)!!.stripOnTop)
    }

    @Test fun aSecondFingerNeverTakesOverTheDrag() {
        val a = task("a")
        val b = task("b")
        val s = beginDrag(null, a, 100f, 300f, 2000f)!!
        assertNull(beginDrag(s, b, 100f, 500f, 2000f))                    // a second drag does not start while one is active
        assertEquals(s, s.moveBy(b.key, 0f, 700f))                          // the other row's moves do not move this drag
        assertNull(dropDay(s.moveBy(a.key, 0f, 750f), b.key, strip, 20f))    // the other row's lift drops nothing
        assertTrue(ownsDrag(s, a.key))
        assertFalse(ownsDrag(s, b.key))
        assertFalse(ownsDrag(null, a.key))
    }

    @Test fun whatAPickedDayLeadsTo() {
        val editable = TaskEditability.Editable
        assertEquals(MoveDecision.Go, moveDecision(editable, today, today, busy = false))
        assertEquals(MoveDecision.OpenPage, moveDecision(TaskEditability.ReadOnly(TaskReadOnlyReason.Parked), today, today, busy = false))
        assertEquals(MoveDecision.OpenPage, moveDecision(null, today, today, busy = false))
        // the picker stayed open over midnight: yesterday is not taken silently, the owner is asked again
        assertEquals(MoveDecision.PickAgain, moveDecision(editable, today.minusDays(1), today, busy = false))
        // another write still runs: said so, not dropped silently
        assertEquals(MoveDecision.Busy, moveDecision(editable, today, today, busy = true))
    }

    @Test fun aRefusedUndoSaysTheUndoWasNotDone() {
        assertEquals(TaskNote.UndoRefused, moveNote(TaskWriteResult.Refused(TaskReadOnlyReason.HasDates), undo = true))
        assertEquals(TaskNote.UndoRefused, moveNote(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), undo = true))
        assertEquals(TaskNote.MoveRefused, moveNote(TaskWriteResult.Refused(TaskReadOnlyReason.ReadOnlyList), undo = false))
        assertEquals(TaskBar.Message(TaskNote.UndoRefused, 9), afterTaskMove(TaskWriteResult.Refused(TaskReadOnlyReason.HasDates), true, "/a.ics", null, today, 9).bar)
    }

    @Test fun onlyDaysTheAppShowsCanBePicked() {
        assertTrue(pickableDay(today, today))
        assertFalse(pickableDay(today.minusDays(1), today))           // a start in the past is not offered here
        val end = loadedWindow(today).end
        assertTrue(pickableDay(end.minusDays(1), today))
        assertFalse(pickableDay(end, today))
        assertEquals(LocalDate.of(2027, 12, 1), end)
        assertEquals(LocalDate.of(2026, 5, 1), loadedWindow(today).first)
    }
}
