package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class RepeatWriterTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val now = LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun ms(day: LocalDate, h: Int, m: Int = 0, zone: ZoneId = athens) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun expand(ics: String, from: LocalDate, to: LocalDate): List<Instance> =
        Expander().expand(
            listOf(ObjectSource(1, "h", ics, "k", false)),
            from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), to.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        ).sortedBy { it.start }

    private fun timed(day: LocalDate, rule: RepeatRule, zone: ZoneId = athens) =
        IcsEdit.newEvent("rep@x", EventDraft("Weekly", EventWhen.Timed(ms(day, 10, 0, zone), ms(day, 11, 0, zone)), "", "", listOf(15), rule, zone.id), now)

    @Test fun aWeeklyTimedEventStaysAtTenAcrossTheEndOfDaylightSaving() {
        // Athens leaves summer time on Sunday 25 Oct 2026: a UTC-written series would jump from 10:00 to 09:00 local
        val ics = timed(LocalDate.of(2026, 10, 15), RepeatRule(RepeatFreq.Weekly, end = RepeatEnd.Count(8)))
        val all = expand(ics, LocalDate.of(2026, 10, 1), LocalDate.of(2027, 1, 31))
        assertEquals(8, all.size)
        for ((i, e) in all.withIndex()) {
            val day = LocalDate.of(2026, 10, 15).plusWeeks(i.toLong())
            assertEquals("occurrence $i", ms(day, 10), e.start); assertEquals(ms(day, 11), e.end)
        }
        assertEquals(listOf(15), all[0].alarmMinutes)
    }

    @Test fun theGeneratedTimeZoneDescribesAthens() {
        val tz = RepeatWriter.vtimezone(athens, now).joinToString("\n")
        assertTrue(tz, "TZID:Europe/Athens" in tz)
        assertTrue(tz, "BEGIN:DAYLIGHT" in tz && "BEGIN:STANDARD" in tz)
        assertTrue(tz, "TZOFFSETFROM:+0200\nTZOFFSETTO:+0300" in tz && "TZOFFSETFROM:+0300\nTZOFFSETTO:+0200" in tz)
        // "the last Sunday" as the rules describe it: a Sunday from the 25th to the 31st (March: 03:00 -> 04:00, October: 04:00 -> 03:00 local)
        assertTrue(tz, "BYMONTH=3;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31" in tz && "BYMONTH=10;BYDAY=SU;BYMONTHDAY=25,26,27,28,29,30,31" in tz)
        assertTrue(tz, "DTSTART:19700329T030000" in tz && "DTSTART:19701025T040000" in tz)
    }

    @Test fun aZoneWithoutSummerTimeGetsOneStandardPart() {
        val tz = RepeatWriter.vtimezone(ZoneId.of("Asia/Tokyo"), now).joinToString("\n")
        assertTrue(tz, "BEGIN:STANDARD" in tz && "DAYLIGHT" !in tz && "TZOFFSETTO:+0900" in tz)
    }

    @Test fun otherZonesKeepLocalTimeToo() {
        val ny = ZoneId.of("America/New_York")
        val ics = timed(LocalDate.of(2026, 10, 29), RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Count(6)), ny)   // US clocks go back on 1 Nov
        val all = expand(ics, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 10))
        assertEquals(6, all.size)
        for ((i, e) in all.withIndex()) assertEquals(ms(LocalDate.of(2026, 10, 29).plusDays(i.toLong()), 10, 0, ny), e.start)
    }

    @Test fun theNewSeriesCanBeEditedByTheSeriesCode() {
        val ics = timed(LocalDate.of(2026, 10, 15), RepeatRule(RepeatFreq.Weekly))
        assertEquals(Editability.EditableSeries, SeriesEdit.editability(ics))
    }

    @Test fun ruleTexts() {
        fun r(rule: RepeatRule, allDay: Boolean = false) = RepeatWriter.rrule(rule, allDay, athens)
        assertEquals("FREQ=DAILY", r(RepeatRule(RepeatFreq.Daily)))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR;COUNT=10", r(RepeatRule(RepeatFreq.Weekly, 2, setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), RepeatEnd.Count(10))))
        assertEquals("FREQ=MONTHLY;INTERVAL=3", r(RepeatRule(RepeatFreq.Monthly, 3)))
        assertEquals("FREQ=YEARLY;UNTIL=20300301", r(RepeatRule(RepeatFreq.Yearly, end = RepeatEnd.Until(LocalDate.of(2030, 3, 1))), allDay = true))
        // a timed series ends at the end of the chosen day, written in UTC (22:00 on 28 Feb in Athens winter time is 21:59:59Z the same evening... end of 1 Mar local = 21:59:59Z)
        assertEquals("FREQ=YEARLY;UNTIL=20300301T215959Z", r(RepeatRule(RepeatFreq.Yearly, end = RepeatEnd.Until(LocalDate.of(2030, 3, 1)))))
    }

    @Test fun theLastChosenDayIsIncludedWhenItEndsOnADayWithAnEvent() {
        val ics = timed(LocalDate.of(2026, 11, 2), RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Until(LocalDate.of(2026, 11, 6))))
        assertEquals(5, expand(ics, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)).size)
    }

    @Test fun aWeeklyRuleThatSkipsTheStartWeekdayStartsOnItsFirstChosenDay() {
        // 15 Oct 2026 is a Thursday; chosen days Monday and Wednesday: the first event is Monday 19 Oct
        val ics = timed(LocalDate.of(2026, 10, 15), RepeatRule(RepeatFreq.Weekly, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), end = RepeatEnd.Count(4)))
        val all = expand(ics, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1))
        assertEquals(listOf(19, 21, 26, 28).map { ms(LocalDate.of(2026, 10, it), 10) }, all.map { it.start })
    }

    @Test fun anAllDayRepeatKeepsDates() {
        val ics = IcsEdit.newEvent("a@x", EventDraft("Pay", EventWhen.AllDay(LocalDate.of(2026, 11, 28), LocalDate.of(2026, 11, 28)), "", "", emptyList(), RepeatRule(RepeatFreq.Monthly, end = RepeatEnd.Count(4)), athens.id), now)
        assertTrue("RRULE:FREQ=MONTHLY;COUNT=4" in ics && "VTIMEZONE" !in ics)
        val all = expand(ics, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 4, 1))
        assertEquals(4, all.size); assertTrue(all.all { it.allDay })
        assertEquals(Editability.EditableSeries, SeriesEdit.editability(ics))
    }

    @Test fun aSingleEventIsWrittenAsBefore() {
        val ics = IcsEdit.newEvent("s@x", EventDraft("One", EventWhen.Timed(ms(LocalDate.of(2026, 11, 20), 10), ms(LocalDate.of(2026, 11, 20), 11)), "", "", emptyList(), null, athens.id), now)
        assertTrue("VTIMEZONE" !in ics && "RRULE" !in ics && "DTSTART:2026" in ics)
    }

    @Test fun badRulesAreRefused() {
        val day = LocalDate.of(2026, 11, 20)
        assertNotNull(RepeatRule(RepeatFreq.Daily, 0).problem(day))
        assertNotNull(RepeatRule(RepeatFreq.Daily, 100).problem(day))
        assertNotNull(RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Count(0)).problem(day))
        assertNotNull(RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Until(day.minusDays(1))).problem(day))
        assertNull(RepeatRule(RepeatFreq.Daily, end = RepeatEnd.Until(day)).problem(day))
        try { timed(day, RepeatRule(RepeatFreq.Daily, 0)); fail() } catch (e: IllegalArgumentException) { }
    }
}
