# Phase 3 design: create, edit and delete events (15 Nov 2026)

Goal: The owner can add an event with the "+" button, change it and delete it, in his Nextcloud calendars. Google feeds and Contact birthdays stay read-only. Repeating events are **not edited yet** (Phase 4). This is the first phase that writes to his real calendars, so safety comes before features.

## 1. Scope
| In Phase 3 (version 0.2.0) | Not yet (and why) |
|---|---|
| "+" button, new-event form: title, calendar (only writable Nextcloud calendars), all-day, start and end (date and time pickers, 24 h), place, notes, reminders (presets, saved in the event) | Repeat rule editor and "this / this and following / all" edits: Phase 4 |
| Edit and delete of **non-repeating** events (pencil and delete on the event page) | Editing or deleting repeating events: shown read-only with a note until Phase 4 (editing one instance wrongly could damage a series) |
| Conflict handling (someone else changed the event): never overwrite silently | Per-event colour, drag to move or resize: Phase 2 grid and later |
| Failed save keeps what the owner typed | **Offline queue: not in 0.2.0.** A save needs the server (Tailscale on); if it fails, the form stays open with a plain message. A queue is a later step because it needs conflict rules for events that changed while the phone was away |

Reminders: the form writes standard reminders (VALARM) into the event. Phase 3 does **not** make the phone ring: Android alarms for reminders are Phase 4. The form says so in one line.

## 2. Rules that keep the real calendars safe
1. **Create** uses `PUT` with `If-None-Match: *` (never replaces an existing object). A new UID (random) and a new address (`<calendar>/<uid>.ics`).
2. **Edit** uses `PUT` with `If-Match: <the ETag we read>`. **Delete** uses `DELETE` with `If-Match`. Nextcloud answers 412 when the event changed meanwhile: the app re-reads it, tells the owner "this event was changed elsewhere", and offers **Keep my version** (repeat the save with the new ETag, after he chose it) or **Use the other version** (discard his edit). Never silent.
3. **Edit keeps everything it does not own.** The event is edited as parsed iCalendar (biweekly), only SUMMARY, DTSTART, DTEND, LOCATION, DESCRIPTION, the reminders, LAST-MODIFIED and SEQUENCE are touched; all other properties (X- properties, colour, attendees, categories, URL...) are written back as they were. A JVM test reads **every real event** from the private Phase 0 files, edits one field, writes it back, and checks that no other property changed (counts only; skipped when the files are absent).
4. **Times are written in UTC** for timed events (`DTSTART:...Z`) and as dates for all-day events: valid everywhere, no VTIMEZONE needed, no network call to fetch time-zone data. The owner sees them in his own zone.
5. Only calendars with `editable = true` can receive or change events. Google, feeds and Contact birthdays never can. The form is not offered for read-only events.
6. After a successful write the local cache is updated at once from the server's answer (new ETag), so the Schedule changes immediately; the next sync reconciles.
7. **No test touches Personal.** Live tests run in a throw-away calendar "BookahCalendar test" that is created with **The owner's yes**, and deleted together with its trash entries at the end. The live test reads the app password from the Windows Credential Manager through a script and never prints it.

## 2b. Changes after the advisor review (15 Nov), these override section 2 where they differ
1. **Editable means the whole stored object is simple**, not only the shown instance. An object is read-only (with a one-line reason) if it has more than one VEVENT, any RECURRENCE-ID, RRULE, RDATE or EXDATE, no VEVENT (a task), or **any ORGANIZER or ATTENDEE** (Nextcloud can send invitation or cancellation e-mails to real people when such an event is changed or deleted; editing those is out of 0.2.0). Unit-tested with a master plus an edited occurrence.
2. **Edit writes only what changed**, by changing the stored text itself line by line (not by re-writing the whole object): a property the owner did not change stays byte-for-byte as it was, including DTSTART/DTEND with their time zone. Changing the end replaces DURATION when the event used DURATION (never both). Reminders: the form manages only reminders of the kind it shows (display alarm, a time before the start); every other alarm (e-mail, absolute, relative to the end, after the start) is left untouched. UTC (rule 4) applies only to times the owner actually sets.
3. **Round-trip test over every real object** (private Phase 0 files, counts only): "edit nothing" gives identical text; editing each owned field leaves all other lines identical and the object still expands to the expected occurrence. It prints how many real objects use DURATION, have ORGANIZER or ATTENDEE, or have other kinds of alarm.
4. **Write path:** if the server's answer has no ETag (it changed the stored object), fetch the object again before saving it in the cache; never keep a guessed ETag. All writes run under the same lock as the sync (one request stream). After a 412 the object is fetched again; if it is gone (404) the dialog offers "create it again" or "discard". When Save is pressed the ETag is read from the cache then, not from the page that was opened earlier.
5. **All-day end:** the form shows the last day; the file gets the day after (DTEND is exclusive). Tests: one day, several days, end before start (refused), a timed event ending at midnight.
6. **Test set-up:** no HTTP or self-signed path, not even in debug. Emulator UI tests use a fake server object injected in the debug source set only (like the seed receiver). The live test from the PC drives the real client against the throw-away calendar, one request at a time, prints counts and status codes only, and after the clean-up reads the trash bin to check that the test items are gone.
7. **Expectations:** reminders are saved but the phone does not ring until Phase 4; no offline queue in 0.2.0 (recorded in the Obsidian plan note as a change from the plan).

## 3. Screens (docs/design-spec.md 2.7, kept simple)
- **"+" button:** rounded square 56 dp, bottom right, in Schedule and Month. Opens the form for today (Month: for the day last tapped).
- **Form (full screen):** X at the left, **Save** pill at the right; large title field; calendar chips; All-day switch; start and end rows (date left, time right; Material pickers); place; reminders (chips with X, "Add reminder" menu: at the time, 5 min, 10 min, 15 min, 30 min, 1 h, 1 day); notes. Unsaved changes ask before closing.
- **Event page:** pencil and a "..." menu with Delete (confirmation dialog) for editable, non-repeating events; for repeating ones the pencil is hidden and a line says editing repeating events comes later.

## 4. Build order
1. Core: event writer (new and edit, preserving other properties) with unit tests and the real-data round-trip test.
2. Core: PUT/DELETE in the CalDAV client with `If-Match` / `If-None-Match`, 412 handling, tested against MockWebServer.
3. App: form, "+" button, edit, delete, conflict dialog, Greek and English strings.
4. Emulator test against a fake server (MockWebServer on the PC through `adb reverse` is not possible over HTTPS only: the emulator build gets a debug-only switch to allow a local test server); then, **with the owner's yes**, the live test in the throw-away calendar from the PC.
5. Release 0.2.0 after the owner's yes; he checks creating, editing and deleting on the phone in a test calendar first.

## 5. Risks
- Round-trip loss in biweekly (mitigated by rule 3 and the real-data test).
- Daylight-saving and all-day edge cases (tests for all-day multi-day, end before start, midnight).
- Nextcloud's own scheduling or sharing side effects (Personal is shared with hermes): events are written without attendees, so no invitations are sent.
