package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class EventColorTest {
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val start = LocalDate.of(2026, 11, 20).atTime(10, 15).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun expand(ics: String): List<Instance> = Expander().expand(
        listOf(ObjectSource(1, "h", ics, "k", false)),
        LocalDate.of(2026, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), LocalDate.of(2027, 2, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    ).sortedBy { it.start }

    private fun draft(color: String?, repeat: RepeatRule? = null) =
        EventDraft("Colour", EventWhen.Timed(start, start + 3_600_000L), "", "", listOf(0), repeat, ZoneId.of("Europe/Athens").id, color)

    @Test fun namesAndHexValuesResolve() {
        assertEquals("#FF6347", EventColors.resolve("tomato")); assertEquals("#FF6347", EventColors.resolve(" Tomato "))
        assertEquals("#112233", EventColors.resolve("#112233")); assertEquals("#AABBCC", EventColors.resolve("#abc"))
        assertEquals("#FF0000", EventColors.resolve("red"))
        assertNull(EventColors.resolve("notacolour")); assertNull(EventColors.resolve("")); assertNull(EventColors.resolve(null))
        assertEquals("tomato", EventColors.choiceFor("#ff6347")?.name); assertNull(EventColors.choiceFor("#123456"))
    }

    @Test fun aNewEventWithAColourWritesAndReadsIt() {
        val ics = IcsEdit.newEvent("c@x", draft("royalblue"), now)
        assertTrue("COLOR:royalblue" in ics)
        assertEquals("#4169E1", expand(ics).single().colorHex)
    }

    @Test fun noColourMeansNoProperty() {
        val ics = IcsEdit.newEvent("c@x", draft(null), now)
        assertFalse("COLOR" in ics); assertNull(expand(ics).single().colorHex)
        assertFalse("COLOR" in IcsEdit.newEvent("c@x", draft("not-in-the-palette"), now))        // only palette names are ever written
    }

    @Test fun aRepeatingEventKeepsItsColour() {
        val ics = IcsEdit.newEvent("c@x", draft("gold", RepeatRule(RepeatFreq.Weekly, end = RepeatEnd.Count(3))), now)
        assertEquals(listOf("#FFD700", "#FFD700", "#FFD700"), expand(ics).map { it.colorHex })
    }

    @Test fun editingTheColourChangesOnlyThatLine() {
        val ics = IcsEdit.newEvent("c@x", draft("tomato"), now)
        val changed = IcsEdit.applyEdit(ics, EventEdit(color = "seagreen"), now + 1000)
        assertTrue("COLOR:seagreen" in changed && "COLOR:tomato" !in changed)
        assertEquals("#2E8B57", expand(changed).single().colorHex)
        val removed = IcsEdit.applyEdit(changed, EventEdit(color = ""), now + 2000)
        assertFalse("COLOR" in removed); assertNull(expand(removed).single().colorHex)
        val added = IcsEdit.applyEdit(removed, EventEdit(color = "orange"), now + 3000)
        assertEquals("#FFA500", expand(added).single().colorHex)
        assertTrue(EventEdit().isEmpty); assertFalse(EventEdit(color = "").isEmpty)
    }

    @Test fun aSeriesColourCanChangeForOneOrAllOccurrences() {
        val ics = IcsEdit.newEvent("c@x", draft("tomato", RepeatRule(RepeatFreq.Weekly, end = RepeatEnd.Count(4))), now)
        val first = expand(ics)
        val occ = Occurrence(first[1].originalStart, first[1].start, first[1].end)
        val one = SeriesEdit.edit(ics, occ, EventEdit(color = "purple"), Scope.This, now + 1000, "new-uid") as SeriesChange.Replace
        assertEquals(listOf("#FF6347", "#800080", "#FF6347", "#FF6347"), expand(one.ics).map { it.colorHex })
        val all = SeriesEdit.edit(ics, occ, EventEdit(color = "gray"), Scope.All, now + 1000, "new-uid") as SeriesChange.Replace
        assertEquals(List(4) { "#808080" }, expand(all.ics).map { it.colorHex })
    }
}
