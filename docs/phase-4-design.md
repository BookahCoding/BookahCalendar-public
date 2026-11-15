# Phase 4 design: repeating events, repeat editor, delete everywhere, phone reminders (15 Nov 2026)

the owner (15 Nov): "Go on with phase 4 and add deleting events too." Phase 3 deliberately left three kinds of events untouchable: repeating events (and edited occurrences), events with other people, and (by design) feed events. This phase handles the first two as far as is safe, and makes reminders ring on the phone.

## 1. What changes for the owner
| Feature | Rule |
|---|---|
| Repeat editor in the form (new events and whole series) | None, every day, week, month, year; custom: every N, chosen weekdays, ends never / on a date / after N times |
| **Edit** a repeating event | Ask **"This event / This and following events / All events"** (this one preselected), like Google Calendar |
| **Delete** a repeating event | The same three choices, with a confirmation |
| **Delete** an event with other people (organiser or attendees) | Allowed **only after a strong warning** ("Nextcloud may send a message to the other people"); **editing** such events stays blocked |
| Feed events (Google) and Contact birthdays | Never changed or deleted (unchanged) |
| Reminders | Real phone notifications for the reminders saved in events, with Open and Snooze (10 min) actions; survive a restart of the phone |

## 2. Safety rules (they extend phase-3-design.md section 2 and 2b)
1. **Every write is conditional** (If-Match / If-None-Match) and every conflict goes to the same dialog as before. Nothing is ever overwritten silently.
2. **Text surgery, not re-writing.** A change touches only the lines it must; every other line of the object stays as it was. The new code works on a whole calendar object with several VEVENTs (the series master and its edited occurrences), not on the first VEVENT only.
3. **A series is editable only if it is simple:** exactly one master VEVENT with exactly one RRULE; no RDATE; no ORGANIZER or ATTENDEE anywhere in the object; every date-time in a time zone the phone can resolve (UTC, floating, or an IANA TZID); edited occurrences (RECURRENCE-ID) are allowed. Anything else stays read-only with the reason shown.
4. **The occurrence is identified by its original start**, written in the same form as the master's DTSTART (UTC `...Z`, `TZID=zone:local time`, or a date), so the server matches it exactly. Local times keep their time zone, so a weekly 10:00 meeting stays at 10:00 across daylight-saving changes.
5. **The three choices**
   - *This event*: delete = add an EXDATE to the master (and drop an existing override of that occurrence); edit = add or change one override VEVENT with `RECURRENCE-ID`, copying the master's fields and applying only the changes.
   - *All events*: delete = delete the object; edit = change the master only (time changes move the whole series by the same amount, in local time); existing overrides keep their own values (documented).
   - *This and following*: delete = end the master before this occurrence (`UNTIL`, COUNT removed, later EXDATEs and overrides dropped); if it is the first occurrence this is the same as deleting all. Edit = end the master before this occurrence **and** create a new object (new UID) that starts at this occurrence with the changes, the same RRULE (COUNT reduced by the occurrences already used) and the later overrides moved to it. This is two writes: the new object is created first; if the second write fails the new one is deleted again; the user is told if anything could not be undone.
6. **Proof on real data.** A JVM test takes every real repeating object from the private Phase 0 files (counts only) and runs each operation on a few of its occurrences, then expands the result and checks: exactly the expected occurrences exist, nothing else moved, other lines unchanged. Skipped when the files are absent.
7. **Live test** (needs the owner's yes, same throw-away calendar method as phase 3): create a repeating event, edit one occurrence, edit this and following, delete this, delete following, delete all, then remove the calendar for good.
8. **Reminders** never touch the server: the phone schedules alarms from the events it already has (expanded for the next 14 days) with `AlarmManager`; a change in the events (sync, edit, delete) reschedules them. Android asks for the notification permission once. Alarms use the exact-alarm permission that calendar apps may use. HyperOS may still limit background work: measured on the phone, not promised.

## 3. Build order
1. Core: multi-VEVENT parser and the occurrence operations (delete this / following, edit this / following / all) with unit tests, then the real-data test.
2. Core: `EventWriter` support for the three-choice writes (two-write split with compensation), tested with the in-memory fake server.
3. App: scope dialog ("this / following / all"), delete for repeating events and events with people, "Repeats" row and custom editor in the form.
4. App: reminder alarms (scheduler, notification channel, Open and Snooze, boot receiver).
5. Emulator tests (debug fake server), then the live test with the owner's yes, then 0.5.0 for the owner to try.

## 4. Risks
- Time-zone arithmetic (daylight saving, non-IANA time zone names): refused when not resolvable; tested with Athens and UTC around the October and March changes.
- A split is two writes: compensation is best effort and stated.
- Overrides that no longer match any occurrence after a change: left as they are (harmless; the server ignores them) except when they fall after a split.
