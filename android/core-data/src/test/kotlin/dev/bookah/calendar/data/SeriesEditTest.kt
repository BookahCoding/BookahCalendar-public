package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Test shorthand: an occurrence that was never moved (its original slot is where it is shown). */
private fun SeriesEdit.edit(ics: String, start: Long, end: Long, edit: EventEdit, scope: Scope, nowMs: Long, newUid: String) =
    edit(ics, Occurrence(start, start, end), edit, scope, nowMs, newUid)

class SeriesEditTest {
    private val athens = ZoneId.of("Europe/Athens")
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    // a complete definition, like the ones the server writes: summer time (+0300) from the last Sunday of March, winter time (+0200) from the last Sunday of October
    private val tz = "BEGIN:VTIMEZONE\r\nTZID:Europe/Athens\r\nBEGIN:DAYLIGHT\r\nDTSTART:19700329T030000\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0300\r\nEND:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\nDTSTART:19701025T040000\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nTZOFFSETFROM:+0300\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"

    /** weekly on Tuesdays 10:00 Athens, from 6 Oct 2026 (before the 25 Oct clock change), with a reminder and a custom property */
    private fun weekly(extra: String = "", rule: String = "FREQ=WEEKLY;BYDAY=TU", uid: String = "w@x") =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\n$tz" +
            "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261001T000000Z\r\nDTSTART;TZID=Europe/Athens:20261006T100000\r\nDTEND;TZID=Europe/Athens:20261006T110000\r\n" +
            "RRULE:$rule\r\nSUMMARY:Weekly\r\nX-KEEP;A=b:keep me\r\nSEQUENCE:2\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:r\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n" +
            "END:VEVENT\r\n$extra" + "END:VCALENDAR\r\n"

    private fun at(d: LocalDate, h: Int = 10, m: Int = 0) = d.atTime(h, m).atZone(athens).toInstant().toEpochMilli()
    private val oct6 = LocalDate.of(2026, 10, 6)
    private fun tuesday(n: Int) = oct6.plusWeeks(n.toLong())          // 0 = 6 Oct, 3 = 27 Oct (after the clock change), ...

    private fun instances(ics: String, weeks: Int = 12): List<Instance> =
        Expander().expand(listOf(ObjectSource(1, "h", ics, "k", false)), at(oct6) - 86_400_000L, at(oct6.plusWeeks(weeks.toLong())))
            .sortedBy { it.start }

    private fun starts(ics: String, weeks: Int = 12) = instances(ics, weeks).map { it.start }
    private val original get() = starts(weekly())
    private fun replaced(c: SeriesChange?): String = (c as SeriesChange.Replace).ics

    // ------------------------------------------------------------------ editability

    @Test fun aSimpleSeriesIsEditable() = assertEquals(Editability.EditableSeries, SeriesEdit.editability(weekly()))

    @Test fun aSeriesWithPeopleIsNotEditedHere() =
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Invitation), SeriesEdit.editability(weekly().replace("SUMMARY:Weekly", "SUMMARY:Weekly\r\nORGANIZER:mailto:a@example.org")))

    @Test fun aSeriesWithRdateOrTwoRulesOrFloatingTimesIsReadOnly() {
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), SeriesEdit.editability(weekly().replace("SUMMARY:Weekly", "SUMMARY:Weekly\r\nRDATE:20261101T100000Z")))
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), SeriesEdit.editability(weekly().replace("SUMMARY:Weekly", "SUMMARY:Weekly\r\nRRULE:FREQ=DAILY")))
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), SeriesEdit.editability(weekly().replace("DTSTART;TZID=Europe/Athens:", "DTSTART:")))
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), SeriesEdit.editability(weekly().replace("Europe/Athens:20261006T100000", "Mars/Olympus:20261006T100000")))
    }

    // ------------------------------------------------------------------ delete

    @Test fun deleteThisAddsAnExdateInTheSeriesOwnTimeFormAndRemovesOnlyThatOccurrence() {
        val out = replaced(SeriesEdit.delete(weekly(), at(tuesday(2)), Scope.This, now))
        assertTrue("EXDATE;TZID=Europe/Athens:20261020T100000" in out)
        assertEquals(original - at(tuesday(2)), starts(out))
        assertTrue("X-KEEP;A=b:keep me" in out && "TRIGGER:-PT15M" in out)       // everything else untouched
    }

    @Test fun deleteThisAcrossTheClockChangeStaysAtTenLocal() {
        // 27 Oct is after the clocks went back on 25 Oct: still 10:00 Athens, which is 08:00 UTC instead of 07:00
        val out = replaced(SeriesEdit.delete(weekly(), at(tuesday(3)), Scope.This, now))
        assertTrue("EXDATE;TZID=Europe/Athens:20261027T100000" in out)
        assertEquals(original - at(tuesday(3)), starts(out))
    }

    @Test fun deleteThisTwiceDoesNotAddTheSameExdateTwice() {
        val once = replaced(SeriesEdit.delete(weekly(), at(tuesday(2)), Scope.This, now))
        val twice = replaced(SeriesEdit.delete(once, at(tuesday(2)), Scope.This, now))
        assertEquals(1, Regex("EXDATE").findAll(twice).count())
    }

    @Test fun deleteFollowingEndsTheSeriesBeforeThatOccurrenceInUtc() {
        val out = replaced(SeriesEdit.delete(weekly(), at(tuesday(3)), Scope.Following, now))
        assertTrue("UNTIL=" in out && "COUNT=" !in out)
        assertEquals(original.filter { it < at(tuesday(3)) }, starts(out))
        assertEquals(3, starts(out).size)
    }

    @Test fun deleteFollowingFromTheFirstOccurrenceDeletesTheObject() =
        assertEquals(SeriesChange.DeleteObject, SeriesEdit.delete(weekly(), at(tuesday(0)), Scope.Following, now))

    @Test fun deleteAllDeletesTheObject() = assertEquals(SeriesChange.DeleteObject, SeriesEdit.delete(weekly(), at(tuesday(1)), Scope.All, now))

    @Test fun deleteFollowingReplacesACountAndDropsLaterExdatesAndEditedOccurrences() {
        val withExtras = weekly(rule = "FREQ=WEEKLY;BYDAY=TU;COUNT=8").replace(
            "SUMMARY:Weekly", "SUMMARY:Weekly\r\nEXDATE;TZID=Europe/Athens:20261013T100000,20261110T100000",
        ).replace(
            "END:VCALENDAR",
            "BEGIN:VEVENT\r\nUID:w@x\r\nDTSTAMP:20261001T000000Z\r\nRECURRENCE-ID;TZID=Europe/Athens:20261117T100000\r\nDTSTART;TZID=Europe/Athens:20261117T120000\r\nDTEND;TZID=Europe/Athens:20261117T130000\r\nSUMMARY:Moved\r\nEND:VEVENT\r\nEND:VCALENDAR",
        )
        val out = replaced(SeriesEdit.delete(withExtras, at(tuesday(4)), Scope.Following, now))          // from 3 Nov
        assertFalse("COUNT=" in out); assertTrue("EXDATE;TZID=Europe/Athens:20261013T100000" in out)
        assertFalse("20261110" in out); assertFalse("Moved" in out)                                         // the later exdate and the later override are gone
        assertEquals(listOf(at(tuesday(0)), at(tuesday(2)), at(tuesday(3))), starts(out))                   // 13 Oct is still excluded
    }

    // ------------------------------------------------------------------ edit

    @Test fun editThisMakesOneEditedOccurrenceAndLeavesTheRestAlone() {
        val out = replaced(SeriesEdit.edit(weekly(), at(tuesday(2)), at(tuesday(2), 11), EventEdit(title = "Only this one"), Scope.This, now, "unused"))
        val got = instances(out)
        assertEquals(original, got.map { it.start })                                                       // same times
        assertEquals(listOf("Only this one"), got.filter { it.title != "Weekly" }.map { it.title })
        assertEquals(at(tuesday(2)), got.single { it.title == "Only this one" }.start)
        assertTrue("RECURRENCE-ID;TZID=Europe/Athens:20261020T100000" in out)
        assertTrue("SUMMARY:Weekly" in out)                                                                // the master keeps its title
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(out).count())
    }

    @Test fun editThisCanMoveJustThatOccurrence() {
        val out = replaced(SeriesEdit.edit(weekly(), at(tuesday(2)), at(tuesday(2), 11), EventEdit(whenAt = EventWhen.Timed(at(tuesday(2), 14), at(tuesday(2), 15))), Scope.This, now, "unused"))
        val got = instances(out)
        assertEquals(((original - at(tuesday(2))) + at(tuesday(2), 14)).sorted(), got.map { it.start }.sorted())
        assertTrue("DTSTART;TZID=Europe/Athens:20261020T140000" in out)                                      // written in the series' own zone form
    }

    @Test fun editThisTwiceChangesTheSameEditedOccurrence() {
        val first = replaced(SeriesEdit.edit(weekly(), at(tuesday(2)), at(tuesday(2), 11), EventEdit(title = "One"), Scope.This, now, "u"))
        val second = replaced(SeriesEdit.edit(first, at(tuesday(2)), at(tuesday(2), 11), EventEdit(location = "Room"), Scope.This, now, "u"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(second).count())
        val o = instances(second).single { it.start == at(tuesday(2)) }
        assertEquals("One", o.title); assertEquals("Room", o.location)
    }

    @Test fun editAllChangesTheSeriesAndKeepsExistingEditedOccurrences() {
        val withOverride = SeriesEdit.edit(weekly(), at(tuesday(2)), at(tuesday(2), 11), EventEdit(title = "Special"), Scope.This, now, "u") as SeriesChange.Replace
        val out = replaced(SeriesEdit.edit(withOverride.ics, at(tuesday(1)), at(tuesday(1), 11), EventEdit(title = "Renamed", location = "Hall"), Scope.All, now, "u"))
        val got = instances(out)
        assertEquals(original, got.map { it.start })
        assertTrue(got.filter { it.start != at(tuesday(2)) }.all { it.title == "Renamed" && it.location == "Hall" })
        assertEquals("Special", got.single { it.start == at(tuesday(2)) }.title)
        assertTrue("X-KEEP;A=b:keep me" in out && "TRIGGER:-PT15M" in out)
    }

    @Test fun editAllMovesTheWholeSeriesByTheSameAmountInLocalTime() {
        // move the 20 Oct occurrence from 10:00 to 11:30: every Tuesday becomes 11:30 Athens, also after the clock change
        val out = replaced(SeriesEdit.edit(weekly(), at(tuesday(2)), at(tuesday(2), 11), EventEdit(whenAt = EventWhen.Timed(at(tuesday(2), 11, 30), at(tuesday(2), 12, 30))), Scope.All, now, "u"))
        assertEquals(original.indices.map { i -> at(tuesday(i), 11, 30) }, starts(out))
        assertTrue("DTSTART;TZID=Europe/Athens:20261006T113000" in out && "DTEND;TZID=Europe/Athens:20261006T123000" in out)
    }

    @Test fun editAllCanChangeTheLengthOfEveryOccurrence() {
        val out = replaced(SeriesEdit.edit(weekly(), at(tuesday(1)), at(tuesday(1), 11), EventEdit(whenAt = EventWhen.Timed(at(tuesday(1)), at(tuesday(1), 12))), Scope.All, now, "u"))
        assertTrue(instances(out).all { it.end - it.start == 2 * 3_600_000L })
    }

    @Test fun editFollowingSplitsIntoTwoObjectsWithoutLosingOrDoublingAnyOccurrence() {
        val change = SeriesEdit.edit(weekly(), at(tuesday(3)), at(tuesday(3), 11), EventEdit(title = "From now on"), Scope.Following, now, "new-uid") as SeriesChange.Split
        val early = instances(change.oldIcs); val late = instances(change.newIcs)
        assertEquals(original.filter { it < at(tuesday(3)) }, early.map { it.start })
        assertEquals(original.filter { it >= at(tuesday(3)) }, late.map { it.start })
        assertTrue(early.all { it.title == "Weekly" }); assertTrue(late.all { it.title == "From now on" })
        assertEquals("new-uid", IcsEdit.uid(change.newIcs)); assertEquals("w@x", IcsEdit.uid(change.oldIcs))
        assertTrue("UNTIL=" in change.oldIcs)
        assertTrue("TZID:Europe/Athens" in change.newIcs)                                                    // the time zone definition travels with it
        assertTrue("X-KEEP;A=b:keep me" in change.newIcs && "TRIGGER:-PT15M" in change.newIcs)
    }

    @Test fun editFollowingSharesOutACountSoTheTotalStaysTheSame() {
        val counted = weekly(rule = "FREQ=WEEKLY;BYDAY=TU;COUNT=8")
        val change = SeriesEdit.edit(counted, at(tuesday(3)), at(tuesday(3), 11), EventEdit(title = "B"), Scope.Following, now, "n") as SeriesChange.Split
        assertEquals(3, instances(change.oldIcs, 20).size)
        assertEquals(5, instances(change.newIcs, 20).size)
        assertEquals(original.take(0) + starts(counted, 20), (instances(change.oldIcs, 20) + instances(change.newIcs, 20)).map { it.start }.sorted())
    }

    @Test fun editFollowingMovesLaterEditedOccurrencesToTheNewObject() {
        val withOverride = (SeriesEdit.edit(weekly(), at(tuesday(5)), at(tuesday(5), 11), EventEdit(title = "Special"), Scope.This, now, "u") as SeriesChange.Replace).ics
        val change = SeriesEdit.edit(withOverride, at(tuesday(3)), at(tuesday(3), 11), EventEdit(title = "B"), Scope.Following, now, "n") as SeriesChange.Split
        assertFalse("Special" in change.oldIcs)
        assertTrue("Special" in change.newIcs)
        assertEquals("n", Regex("UID:(\\S+)").findAll(change.newIcs).map { it.groupValues[1] }.toSet().single())          // every event of the new object has the new UID
    }

    @Test fun editFollowingFromTheFirstOccurrenceIsTheSameAsEditAll() {
        val c = SeriesEdit.edit(weekly(), at(tuesday(0)), at(tuesday(0), 11), EventEdit(title = "All"), Scope.Following, now, "n")
        assertTrue(c is SeriesChange.Replace)
        assertTrue(instances(replaced(c)).all { it.title == "All" })
    }

    @Test fun anIllegalChangeIsRefusedAndNothingIsReturned() {
        try { SeriesEdit.edit(weekly(), at(tuesday(1)), at(tuesday(1), 11), EventEdit(whenAt = EventWhen.AllDay(oct6, oct6)), Scope.All, now, "n"); fail() } catch (e: IllegalArgumentException) { }
        try { SeriesEdit.edit(weekly(), at(tuesday(1)), at(tuesday(1), 11), EventEdit(whenAt = EventWhen.Timed(5, 1)), Scope.This, now, "n"); fail() } catch (e: IllegalArgumentException) { }
        assertNull(SeriesEdit.edit("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:p\r\nDTSTART:20261120T100000Z\r\nSUMMARY:Plain\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n", 0, 0, EventEdit(title = "x"), Scope.This, now, "n"))
    }

    // ------------------------------------------------------------------ all-day yearly series (like a birthday)

    private val birthday = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:b@x\r\nDTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:20200304\r\nDTEND;VALUE=DATE:20200305\r\nRRULE:FREQ=YEARLY\r\nSUMMARY:Birthday\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun day(y: Int) = LocalDate.of(y, 3, 4).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun birthdays(ics: String) = Expander().expand(listOf(ObjectSource(1, "h", ics, "k", false)), day(2020) - 86_400_000L, day(2030)).sortedBy { it.start }

    @Test fun aYearlyAllDaySeriesCanBeCutAndEdited() {
        assertEquals(Editability.EditableSeries, SeriesEdit.editability(birthday))
        val del = replaced(SeriesEdit.delete(birthday, day(2024), Scope.This, now))
        assertTrue("EXDATE;VALUE=DATE:20240304" in del)
        assertFalse(birthdays(del).any { it.start == day(2024) }); assertTrue(birthdays(del).any { it.start == day(2025) })
        val fol = replaced(SeriesEdit.delete(birthday, day(2024), Scope.Following, now))
        assertTrue("UNTIL=20240303" in fol)                                                                  // the day before, as a date
        assertEquals((2020..2023).map { day(it) }, birthdays(fol).map { it.start })
    }

    @Test fun editingOneYearOfAnAllDaySeriesWritesDateForms() {
        val out = replaced(SeriesEdit.edit(birthday, day(2024), day(2024) + 86_400_000L, EventEdit(title = "Party"), Scope.This, now, "u"))
        assertTrue("RECURRENCE-ID;VALUE=DATE:20240304" in out)
        assertEquals("Party", birthdays(out).single { it.start == day(2024) }.title)
        val moved = replaced(SeriesEdit.edit(birthday, day(2024), day(2024) + 86_400_000L, EventEdit(whenAt = EventWhen.AllDay(LocalDate.of(2024, 3, 6), LocalDate.of(2024, 3, 6))), Scope.All, now, "u"))
        assertEquals((2020..2029).map { LocalDate.of(it, 3, 6).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }, birthdays(moved).map { it.start }.filter { it < day(2030) })
    }
}
