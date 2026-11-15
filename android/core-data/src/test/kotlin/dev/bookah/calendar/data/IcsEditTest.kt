package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class IcsEditTest {
    private val now = LocalDate.of(2026, 11, 15).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    private val start = LocalDate.of(2026, 11, 20).atTime(10, 15).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val end = start + 3_600_000L

    private fun expand(ics: String, from: LocalDate = LocalDate.of(2026, 11, 1), to: LocalDate = LocalDate.of(2026, 12, 31)): List<Instance> =
        Expander().expand(
            listOf(ObjectSource(1, "h", ics, "k", false)),
            from.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(), to.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )

    private val simple = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//x//EN\r\nBEGIN:VEVENT\r\nUID:one@x\r\nDTSTAMP:20261101T000000Z\r\n" +
        "DTSTART;TZID=Europe/Athens:20261120T101500\r\nDTEND;TZID=Europe/Athens:20261120T111500\r\nSUMMARY:Old title\r\nX-CUSTOM;FOO=bar:keep me\r\n" +
        "SEQUENCE:3\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun lines(ics: String) = IcsEdit.logicalLines(ics)

    // ------------------------------------------------------------ new events

    @Test fun aNewTimedEventIsValidAndReadsBack() {
        val ics = IcsEdit.newEvent("new@x", EventDraft("Dentist", EventWhen.Timed(start, end), "Ermou 10, Athens", "Bring the card", listOf(15, 1440)), now)
        val e = expand(ics).single()
        assertEquals("Dentist", e.title)
        assertEquals(start, e.start); assertEquals(end, e.end); assertFalse(e.allDay)
        assertEquals("Ermou 10, Athens", e.location); assertEquals("Bring the card", e.description)
        assertEquals(listOf(1440, 15), e.alarmMinutes)
        assertEquals(Editability.Editable, IcsEdit.editability(ics))
    }

    @Test fun aNewAllDayEventWritesTheDayAfterAsTheEnd() {
        val ics = IcsEdit.newEvent("d@x", EventDraft("Trip", EventWhen.AllDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22)), "", "", emptyList()), now)
        assertTrue("DTEND;VALUE=DATE:20261123" in ics)
        val e = expand(ics).single()
        assertTrue(e.allDay)
        assertEquals(LocalDate.of(2026, 11, 20).toEpochDay() * 86_400_000L, e.start)
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay() * 86_400_000L, e.end)
    }

    @Test fun anEndBeforeTheStartIsRefused() {
        assertEquals("end before start", IcsEdit.validate(EventWhen.Timed(end, start)))
        assertEquals("end before start", IcsEdit.validate(EventWhen.AllDay(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 20))))
        assertNull(IcsEdit.validate(EventWhen.Timed(start, start)))
        try { IcsEdit.newEvent("x", EventDraft("t", EventWhen.Timed(end, start), "", "", emptyList()), now); fail() } catch (e: IllegalArgumentException) { }
    }

    @Test fun anEventEndingAtMidnightStaysOnItsDay() {
        val s = LocalDate.of(2026, 11, 20).atTime(22, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val e = LocalDate.of(2026, 11, 21).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val ics = IcsEdit.newEvent("m@x", EventDraft("Late", EventWhen.Timed(s, e), "", "", emptyList()), now)
        val got = expand(ics).single()
        assertEquals(s, got.start); assertEquals(e, got.end)
    }

    @Test fun commasSemicolonsNewlinesGreekAndLongTitlesSurviveWritingAndReading() {
        val title = "Συνάντηση, με; τον \\ Κώστα ".repeat(6).trim()
        val notes = "line one\nline two, with; marks"
        val ics = IcsEdit.newEvent("t@x", EventDraft(title, EventWhen.Timed(start, end), "Αθήνα, Ελλάδα", notes, emptyList()), now)
        assertTrue("no line longer than 75 bytes", ics.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        val e = expand(ics).single()
        assertEquals(title, e.title); assertEquals("Αθήνα, Ελλάδα", e.location); assertEquals(notes, e.description)
    }

    // ------------------------------------------------------------ what may be edited

    private fun wrap(vevents: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$vevents" + "END:VCALENDAR\r\n"
    private fun ev(extra: String = "") = "BEGIN:VEVENT\r\nUID:a@x\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261120T100000Z\r\nDTEND:20261120T110000Z\r\nSUMMARY:S\r\n$extra" + "END:VEVENT\r\n"

    @Test fun aSimpleEventIsEditable() = assertEquals(Editability.Editable, IcsEdit.editability(wrap(ev())))

    @Test fun repeatingAndEditedOccurrencesAreReadOnly() {
        for (extra in listOf("RRULE:FREQ=WEEKLY\r\n", "RDATE:20261201T100000Z\r\n", "EXDATE:20261201T100000Z\r\n", "RECURRENCE-ID:20261127T100000Z\r\n")) {
            assertEquals("for $extra", Editability.ReadOnly(ReadOnlyReason.Repeating), IcsEdit.editability(wrap(ev(extra))))
        }
    }

    @Test fun aMasterWithAnEditedOccurrenceInOneFileIsReadOnly() {
        val file = wrap(ev("RRULE:FREQ=WEEKLY\r\n") + ev("RECURRENCE-ID:20261127T100000Z\r\n"))
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), IcsEdit.editability(file))
        // an override alone (no RRULE of its own) must be read-only too
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), IcsEdit.editability(wrap(ev("RECURRENCE-ID:20261127T100000Z\r\n"))))
    }

    @Test fun twoEventsInOneFileAreReadOnly() =
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Repeating), IcsEdit.editability(wrap(ev() + ev())))

    @Test fun eventsWithPeopleAreReadOnlyBecauseTheServerCouldSendMail() {
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Invitation), IcsEdit.editability(wrap(ev("ORGANIZER:mailto:a@example.org\r\n"))))
        assertEquals(Editability.ReadOnly(ReadOnlyReason.Invitation), IcsEdit.editability(wrap(ev("ATTENDEE;CN=B:mailto:b@example.org\r\n"))))
    }

    @Test fun aTaskIsNotAnEvent() =
        assertEquals(Editability.ReadOnly(ReadOnlyReason.NotAnEvent), IcsEdit.editability(wrap("BEGIN:VTODO\r\nUID:t@x\r\nSUMMARY:T\r\nEND:VTODO\r\n")))

    @Test fun applyEditRefusesAReadOnlyObject() {
        try { IcsEdit.applyEdit(wrap(ev("RRULE:FREQ=DAILY\r\n")), EventEdit(title = "x"), now); fail() } catch (e: IllegalStateException) { }
    }

    // ------------------------------------------------------------ only what changed

    @Test fun noChangeReturnsTheSameText() = assertEquals(simple, IcsEdit.applyEdit(simple, EventEdit(), now))

    @Test fun changingTheTitleLeavesEveryOtherLineAsItWas() {
        val out = IcsEdit.applyEdit(simple, EventEdit(title = "New, title"), now)
        val before = lines(simple).toMutableList(); val after = lines(out)
        val kept = setOf("DTSTAMP", "SEQUENCE", "SUMMARY", "LAST-MODIFIED")
        val unchangedBefore = before.filter { IcsEdit.name(it) !in kept }
        val unchangedAfter = after.filter { IcsEdit.name(it) !in kept }
        assertEquals(unchangedBefore, unchangedAfter)                     // includes DTSTART;TZID=..., DTEND;TZID=..., X-CUSTOM
        assertTrue("DTSTART;TZID=Europe/Athens:20261120T101500" in out)
        assertTrue("X-CUSTOM;FOO=bar:keep me" in out)
        assertEquals("New, title", expand(out).single().title)
        assertTrue("SEQUENCE:4" in out)
    }

    @Test fun changingTheEndReplacesADurationNeverAddsAnEndBesideIt() {
        val withDuration = wrap("BEGIN:VEVENT\r\nUID:d@x\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261120T100000Z\r\nDURATION:PT1H\r\nSUMMARY:S\r\nEND:VEVENT\r\n")
        val out = IcsEdit.applyEdit(withDuration, EventEdit(whenAt = EventWhen.Timed(start, start + 7_200_000L)), now)
        assertTrue("DURATION" !in out)
        assertEquals(1, lines(out).count { IcsEdit.name(it) == "DTEND" })
        assertEquals(start + 7_200_000L, expand(out).single().end)
    }

    @Test fun remindersTheFormManagesAreChangedAndOtherAlarmsAreNeverTouched() {
        val alarms = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n" +
            "BEGIN:VALARM\r\nACTION:EMAIL\r\nSUMMARY:x\r\nTRIGGER:-PT30M\r\nEND:VALARM\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER;VALUE=DATE-TIME:20261120T090000Z\r\nEND:VALARM\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nTRIGGER:PT10M\r\nEND:VALARM\r\n"
        val file = wrap(ev(alarms))
        assertEquals(listOf(15), IcsEdit.managedReminderMinutes(file))        // only the display alarm before the start is shown
        val out = IcsEdit.applyEdit(file, EventEdit(reminderMinutes = listOf(60)), now)   // remove the 15, add 60
        assertEquals(listOf(60), IcsEdit.managedReminderMinutes(out))
        assertTrue("ACTION:EMAIL" in out); assertTrue("RELATED=END:-PT5M" in out)
        assertTrue("VALUE=DATE-TIME:20261120T090000Z" in out); assertTrue("TRIGGER:PT10M" in out)
        assertFalse("TRIGGER:-PT15M" in out)
        assertTrue("TRIGGER:-PT1H" in out)
    }

    @Test fun keepingAReminderLeavesItsLinesAsTheyWere() {
        val file = wrap(ev("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:My own words\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"))
        val out = IcsEdit.applyEdit(file, EventEdit(reminderMinutes = listOf(15, 1440)), now)
        assertTrue("DESCRIPTION:My own words" in out)
        assertEquals(listOf(1440, 15), IcsEdit.managedReminderMinutes(out))
    }

    @Test fun clearingPlaceAndNotesRemovesThem() {
        val file = wrap(ev("LOCATION:Somewhere\r\nDESCRIPTION:Some notes\r\n"))
        val out = IcsEdit.applyEdit(file, EventEdit(location = "", notes = ""), now)
        assertTrue("LOCATION" !in out); assertTrue("DESCRIPTION" !in out)
    }

    @Test fun triggerTextIsReadCorrectly() {
        assertEquals(15, IcsEdit.minutesBefore("-PT15M")); assertEquals(1440, IcsEdit.minutesBefore("-P1D")); assertEquals(90, IcsEdit.minutesBefore("-PT1H30M"))
        assertEquals(10080, IcsEdit.minutesBefore("-P1W")); assertEquals(0, IcsEdit.minutesBefore("PT0S")); assertEquals(0, IcsEdit.minutesBefore("-PT0S"))
        assertNull(IcsEdit.minutesBefore("PT10M")); assertNull(IcsEdit.minutesBefore("-PT30S")); assertNull(IcsEdit.minutesBefore("nonsense"))
    }
}
