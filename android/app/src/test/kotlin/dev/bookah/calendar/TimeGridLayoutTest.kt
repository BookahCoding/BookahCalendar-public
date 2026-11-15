package dev.bookah.calendar

import dev.bookah.calendar.data.Instance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TimeGridLayoutTest {
    private val zone = ZoneId.of("Europe/Athens")
    private val day = LocalDate.of(2026, 11, 20)

    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ev(title: String, sh: Int, sm: Int, eh: Int, em: Int, d: LocalDate = day, endDay: LocalDate = d) =
        Instance(1, title, "h-$title", title, null, null, ms(d, sh, sm), ms(endDay, eh, em), false, null, null, emptyList(), false)

    private fun allDay(title: String, first: LocalDate, lastInclusive: LocalDate) =
        Instance(1, title, "h-$title", title, null, null, first.toEpochDay() * 86_400_000L, (lastInclusive.toEpochDay() + 1) * 86_400_000L, true, null, null, emptyList(), false)

    private fun place(vararg e: Instance) = layoutDay(e.toList(), day, zone).associateBy { it.event.title }

    @Test fun separateEventsUseTheFullWidth() {
        val p = place(ev("a", 9, 0, 10, 0), ev("b", 11, 0, 12, 0))
        assertEquals(1, p.getValue("a").columns); assertEquals(1, p.getValue("b").columns)
        assertEquals(0, p.getValue("b").column)
    }

    @Test fun twoOverlappingEventsShareTheWidth() {
        val p = place(ev("a", 9, 0, 11, 0), ev("b", 10, 0, 12, 0))
        assertEquals(2, p.getValue("a").columns); assertEquals(2, p.getValue("b").columns)
        assertEquals(setOf(0, 1), setOf(p.getValue("a").column, p.getValue("b").column))
    }

    @Test fun eventsThatOnlyTouchDoNotOverlap() {
        val p = place(ev("a", 9, 0, 10, 0), ev("b", 10, 0, 11, 0))
        assertEquals(1, p.getValue("a").columns); assertEquals(1, p.getValue("b").columns)
    }

    @Test fun aChainKeepsOneClusterAndReusesFreeColumns() {
        // a 9-11, b 10-12, c 11-13: a and c do not overlap, so c can use a's column; the cluster is 2 wide
        val p = place(ev("a", 9, 0, 11, 0), ev("b", 10, 0, 12, 0), ev("c", 11, 0, 13, 0))
        assertEquals(2, p.getValue("a").columns); assertEquals(2, p.getValue("c").columns)
        assertEquals(p.getValue("a").column, p.getValue("c").column)
    }

    @Test fun threeAtTheSameTimeAreThreeColumns() {
        val p = place(ev("a", 9, 0, 10, 0), ev("b", 9, 0, 10, 0), ev("c", 9, 0, 10, 0))
        assertEquals(setOf(0, 1, 2), p.values.map { it.column }.toSet()); assertTrue(p.values.all { it.columns == 3 })
    }

    @Test fun aSeparateLaterClusterIsNotNarrowedByAnEarlierBusyOne() {
        val p = place(ev("a", 9, 0, 10, 0), ev("b", 9, 0, 10, 0), ev("later", 14, 0, 15, 0))
        assertEquals(2, p.getValue("a").columns); assertEquals(1, p.getValue("later").columns)
    }

    @Test fun aVeryShortEventIsDrawnAtLeastTheMinimumHeightAndCountsThatForOverlap() {
        val p = place(ev("tiny", 9, 0, 9, 5), ev("next", 9, 10, 10, 0))
        assertEquals(9 * 60, p.getValue("tiny").startMin); assertEquals(9 * 60 + 25, p.getValue("tiny").endMin)
        assertEquals(2, p.getValue("tiny").columns)                       // the drawn tiny event reaches 09:25, so "next" shares the width
    }

    @Test fun anEventAcrossMidnightIsClippedToEachDay() {
        val night = ev("night", 22, 0, 2, 0, endDay = day.plusDays(1))
        val first = layoutDay(listOf(night), day, zone).single()
        assertEquals(22 * 60, first.startMin); assertEquals(MINUTES_PER_DAY, first.endMin)
        val second = layoutDay(listOf(night), day.plusDays(1), zone).single()
        assertEquals(0, second.startMin); assertEquals(120, second.endMin)
        assertTrue(layoutDay(listOf(night), day.plusDays(2), zone).isEmpty())
    }

    @Test fun anEventEndingExactlyAtMidnightDoesNotAppearOnTheNextDay() {
        val e = ev("late", 22, 0, 0, 0, endDay = day.plusDays(1))
        assertEquals(1, layoutDay(listOf(e), day, zone).size)
        assertTrue(layoutDay(listOf(e), day.plusDays(1), zone).isEmpty())
    }

    @Test fun allDayEventsAreNotInTheGridButInTheStripOnEachOfTheirDays() {
        val trip = allDay("trip", day.minusDays(1), day.plusDays(1))
        assertTrue(layoutDay(listOf(trip), day, zone).isEmpty())
        assertEquals(listOf("trip"), allDayFor(listOf(trip), day).map { it.title })
        assertEquals(listOf("trip"), allDayFor(listOf(trip), day.minusDays(1)).map { it.title })
        assertEquals(listOf("trip"), allDayFor(listOf(trip), day.plusDays(1)).map { it.title })
        assertTrue(allDayFor(listOf(trip), day.plusDays(2)).isEmpty())
        assertTrue(allDayFor(listOf(trip), day.minusDays(2)).isEmpty())
    }

    @Test fun aZeroLengthEventIsStillShown() {
        val p = place(ev("moment", 12, 0, 12, 0))
        assertEquals(12 * 60, p.getValue("moment").startMin); assertTrue(p.getValue("moment").endMin > p.getValue("moment").startMin)
    }
}
