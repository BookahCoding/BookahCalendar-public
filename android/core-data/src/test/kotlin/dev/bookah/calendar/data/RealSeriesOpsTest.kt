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
 * Runs every series operation (delete this / following / all, edit this / following / all) on three occurrences (first, middle, last in a
 * window) of every real repeating event of the owner's Personal calendar that the app would allow to change, expands the result and compares it
 * with what it must be (docs/phase-4-design.md 2.6). Counts only are printed; nothing from the files appears. Skipped without the private files.
 */
class RealSeriesOpsTest {
    private val dir = File(System.getenv("BOOKAH_PRIVATE_DIR") ?: (System.getProperty("user.home") + "/BookahPrivate"))
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val from = LocalDate.of(2025, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val to = LocalDate.of(2029, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val housekeeping = setOf("DTSTAMP", "LAST-MODIFIED", "SEQUENCE")

    private fun objects(xml: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml)
        val n = doc.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
        return (0 until n.length).map { n.item(it).textContent }.filter { "BEGIN:VEVENT" in it }
    }

    private fun expand(ics: String): List<Instance> =
        Expander().expand(listOf(ObjectSource(1, "h", ics, "k", false)), from, to).sortedWith(compareBy({ it.originalStart }, { it.start }))

    private fun replaced(c: SeriesChange?): String = (c as SeriesChange.Replace).ics
    private fun moved(i: Instance): EventWhen =
        if (i.allDay) EventWhen.AllDay(LocalDate.ofEpochDay(Math.floorDiv(i.start, 86_400_000L)).plusDays(1), LocalDate.ofEpochDay(Math.floorDiv(maxOf(i.end - 1, i.start), 86_400_000L)).plusDays(1))
        else EventWhen.Timed(i.start + 3_600_000L, i.end + 3_600_000L)

    @Test fun everyOperationOnEveryEditableRealSeriesGivesExactlyTheExpectedOccurrences() {
        val raw = File(dir, "personal-raw.xml")
        assumeTrue("private Phase 0 files not present: skipped", raw.exists())
        var series = 0; var skippedEmpty = 0; var occurrencesTried = 0; var checks = 0; var notEditable = 0; var splits = 0

        for (ics in objects(raw)) {
            if (SeriesEdit.editability(ics) != Editability.EditableSeries) { if (IcsEdit.editability(ics) is Editability.ReadOnly) notEditable++; continue }
            val all = expand(ics)
            if (all.isEmpty()) { skippedEmpty++; continue }
            series++
            val master = SeriesEdit.parseDoc(ics)!!.events.map { SeriesEdit.ev(it) }.first { !it.isOverride }
            val masterStart = SeriesEdit.parseValue(SeriesEdit.formOf(master.prop("DTSTART"))!!, IcsEdit.value(master.prop("DTSTART")!!))!!
            val overrideCount = SeriesEdit.parseDoc(ics)!!.events.count { SeriesEdit.ev(it).isOverride }
            val picks = listOf(all.first(), all[all.size / 2], all.last()).distinctBy { it.originalStart }
            val allKeys = all.map { it.originalStart }

            for (inst in picks) {
                occurrencesTried++
                val occ = Occurrence(inst.originalStart, inst.start, inst.end)

                // delete this
                run {
                    val out = replaced(SeriesEdit.delete(ics, inst.originalStart, Scope.This, now))
                    assertEquals("delete this", allKeys - inst.originalStart, expand(out).map { it.originalStart })
                    checks++
                }
                // delete following
                run {
                    val c = SeriesEdit.delete(ics, inst.originalStart, Scope.Following, now)
                    if (inst.originalStart <= masterStart) assertEquals(SeriesChange.DeleteObject, c)
                    else assertEquals("delete following", allKeys.filter { it < inst.originalStart }, expand(replaced(c)).map { it.originalStart })
                    checks++
                }
                // delete all
                assertEquals(SeriesChange.DeleteObject, SeriesEdit.delete(ics, inst.originalStart, Scope.All, now)); checks++

                // edit this (title): only this occurrence changes
                run {
                    val out = replaced(SeriesEdit.edit(ics, occ, EventEdit(title = "Changed"), Scope.This, now, "new-uid"))
                    val got = expand(out)
                    assertEquals("edit this keeps all slots", allKeys, got.map { it.originalStart })
                    for ((before, after) in all.zip(got)) {
                        if (before.originalStart == inst.originalStart) assertEquals("Changed", after.title)
                        else { assertEquals(before.title, after.title); assertEquals(before.start, after.start); assertEquals(before.end, after.end) }
                    }
                    checks++
                }
                // edit this (move): only this occurrence moves
                run {
                    val w = moved(inst)
                    val out = replaced(SeriesEdit.edit(ics, occ, EventEdit(whenAt = w), Scope.This, now, "new-uid"))
                    val got = expand(out)
                    assertEquals(allKeys, got.map { it.originalStart })
                    for ((before, after) in all.zip(got)) {
                        if (before.originalStart == inst.originalStart) {
                            assertEquals(if (w is EventWhen.Timed) w.startMs else before.start + 86_400_000L, after.start)
                        } else { assertEquals(before.start, after.start); assertEquals(before.end, after.end) }
                    }
                    checks++
                }
                // edit all (title): same slots and times; every occurrence that is not an edited copy is renamed
                run {
                    val out = replaced(SeriesEdit.edit(ics, occ, EventEdit(title = "Renamed"), Scope.All, now, "new-uid"))
                    val got = expand(out)
                    assertEquals(allKeys, got.map { it.originalStart })
                    assertEquals(all.map { it.start }, got.map { it.start })
                    assertTrue("edit all renames the series", got.count { it.title == "Renamed" } >= all.size - overrideCount)
                    checks++
                }
                // edit following (title): the two objects together hold exactly the original occurrences
                run {
                    val c = SeriesEdit.edit(ics, occ, EventEdit(title = "FromHere"), Scope.Following, now, "new-uid")
                    if (inst.originalStart <= masterStart) {
                        assertTrue(c is SeriesChange.Replace)
                        assertEquals(allKeys, expand(replaced(c)).map { it.originalStart })
                    } else {
                        splits++
                        val sp = c as SeriesChange.Split
                        val early = expand(sp.oldIcs); val late = expand(sp.newIcs)
                        assertTrue(early.all { it.originalStart < inst.originalStart }); assertTrue(late.all { it.originalStart >= inst.originalStart })
                        assertEquals("split loses or doubles nothing", allKeys, (early + late).map { it.originalStart }.sorted())
                        for (e in early) assertEquals(all.first { it.originalStart == e.originalStart }.title, e.title)
                        assertTrue("the later part carries the change", late.count { it.title == "FromHere" } >= late.size - overrideCount)
                        assertEquals("a split gives the later part its own UID", sp.newUid, IcsEdit.uid(sp.newIcs))
                    }
                    checks++
                }
            }
        }
        println("real series allowed to change=$series (not allowed: $notEditable, empty in the window: $skippedEmpty); occurrences tried=$occurrencesTried; checks passed=$checks; splits checked=$splits")
        assertTrue(series > 0)
    }
}
