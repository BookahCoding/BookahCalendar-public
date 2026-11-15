package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Runs the event writer over every real stored event of the owner's Personal calendar (Phase 0 files, outside the repository) and checks the
 * promises of docs/phase-3-design.md section 2b: "edit nothing" changes nothing; changing one field changes only that field (every other
 * line stays as it was); the object still reads back correctly. Prints counts only: no titles, no ids, nothing from the files.
 */
class RealRoundTripTest {
    private val dir = File(System.getenv("BOOKAH_PRIVATE_DIR") ?: (System.getProperty("user.home") + "/BookahPrivate"))
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun objects(xml: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml)
        val n = doc.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
        return (0 until n.length).map { n.item(it).textContent }.filter { "BEGIN:VEVENT" in it }
    }

    private fun lines(ics: String) = IcsEdit.logicalLines(ics)
    private val houseKeeping = setOf("DTSTAMP", "LAST-MODIFIED", "SEQUENCE")

    private fun expandAll(ics: String): List<Instance> = Expander().expand(
        listOf(ObjectSource(1, "h", ics, "k", false)),
        LocalDate.of(2000, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        LocalDate.of(2100, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )

    /** every line except the ones named, as unfolded text, in order */
    private fun without(ics: String, vararg names: String) = lines(ics).filter { IcsEdit.name(it) !in names }

    @Test fun editingEachFieldOfEveryRealEditableEventChangesOnlyThatField() {
        val raw = File(dir, "personal-raw.xml")
        assumeTrue("private Phase 0 files not present: skipped", raw.exists())
        val all = objects(raw)
        var editable = 0; var repeating = 0; var invitation = 0; var other = 0
        var withDuration = 0; var withOrganizerOrAttendee = 0; var withOtherAlarms = 0; var withTzid = 0; var checkedEdits = 0

        for (ics in all) {
            val body = lines(ics)
            if (body.any { IcsEdit.name(it) == "DURATION" }) withDuration++
            if (body.any { IcsEdit.name(it) == "ORGANIZER" || IcsEdit.name(it) == "ATTENDEE" }) withOrganizerOrAttendee++
            if (body.any { IcsEdit.name(it) == "TRIGGER" } && IcsEdit.managedReminderMinutes(ics).size < body.count { IcsEdit.name(it) == "TRIGGER" }) withOtherAlarms++
            if (body.any { IcsEdit.name(it) == "DTSTART" && "TZID=" in it.uppercase() }) withTzid++

            when (val e = IcsEdit.editability(ics)) {
                is Editability.ReadOnly -> when (e.reason) { ReadOnlyReason.Repeating -> repeating++; ReadOnlyReason.Invitation -> invitation++; else -> other++ }
                Editability.EditableSeries -> error("the plain check never returns a series")
                Editability.Editable -> {
                    editable++
                    assertEquals("edit nothing must change nothing", ics, IcsEdit.applyEdit(ics, EventEdit(), now))
                    val before = expandAll(ics)
                    assertEquals("an editable event is one occurrence", 1, before.size)
                    val inst = before.single()

                    // title
                    var out = IcsEdit.applyEdit(ics, EventEdit(title = "Changed title"), now)
                    assertEquals(without(ics, "SUMMARY", *houseKeeping.toTypedArray()), without(out, "SUMMARY", *houseKeeping.toTypedArray()))
                    expandAll(out).single().let { assertEquals("Changed title", it.title); assertEquals(inst.start, it.start); assertEquals(inst.end, it.end) }
                    // place and notes
                    out = IcsEdit.applyEdit(ics, EventEdit(location = "New place", notes = "New notes"), now)
                    assertEquals(without(ics, "LOCATION", "DESCRIPTION", *houseKeeping.toTypedArray()), without(out, "LOCATION", "DESCRIPTION", *houseKeeping.toTypedArray()))
                    expandAll(out).single().let { assertEquals("New place", it.location); assertEquals("New notes", it.description); assertEquals(inst.title, it.title) }
                    // reminders: add one; the other reminders and everything else stay
                    out = IcsEdit.applyEdit(ics, EventEdit(reminderMinutes = (IcsEdit.managedReminderMinutes(ics) + 4321).distinct()), now)
                    assertTrue(4321 in IcsEdit.managedReminderMinutes(out))
                    assertEquals(IcsEdit.managedReminderMinutes(ics).toSet() + 4321, IcsEdit.managedReminderMinutes(out).toSet())
                    // adding a reminder only adds: every original line (except the change-time lines) is still there
                    assertTrue(lines(out).toSet().containsAll(without(ics, *houseKeeping.toTypedArray())))
                    // when: move one hour (timed) or one day (all day)
                    val moved = if (inst.allDay) {
                        val first = LocalDate.ofEpochDay(Math.floorDiv(inst.start, 86_400_000L)).plusDays(1)
                        val last = LocalDate.ofEpochDay(Math.floorDiv(maxOf(inst.end - 1, inst.start), 86_400_000L)).plusDays(1)
                        EventWhen.AllDay(first, last)
                    } else EventWhen.Timed(inst.start + 3_600_000L, inst.end + 3_600_000L)
                    out = IcsEdit.applyEdit(ics, EventEdit(whenAt = moved), now)
                    assertEquals(without(ics, "DTSTART", "DTEND", "DURATION", *houseKeeping.toTypedArray()), without(out, "DTSTART", "DTEND", "DURATION", *houseKeeping.toTypedArray()))
                    expandAll(out).single().let {
                        assertEquals(inst.title, it.title)
                        assertEquals(inst.allDay, it.allDay)
                        assertEquals(if (inst.allDay) inst.start + 86_400_000L else inst.start + 3_600_000L, it.start)
                        assertEquals(if (inst.allDay) inst.end + 86_400_000L else inst.end + 3_600_000L, it.end)
                    }
                    checkedEdits += 4
                }
            }
        }
        println(
            "real objects=${all.size}: editable=$editable, repeating or edited occurrence=$repeating, with people=$invitation, other=$other; " +
                "with DURATION=$withDuration, with ORGANIZER or ATTENDEE=$withOrganizerOrAttendee, with non-form alarms=$withOtherAlarms, with TZID start=$withTzid; edit checks passed=$checkedEdits",
        )
        assertEquals(all.size, editable + repeating + invitation + other)
    }
}
