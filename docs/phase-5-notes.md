# Phase 5 notes (15 Nov 2026)

## Search
Over the months saved on the phone. See `ScheduleModel.kt` (`searchHits`) and `SearchTest`.

## Accessibility
- **Large text:** tried at the largest system text size (2.0) on the emulator. Lists, the event form and the settings follow the full size and wrap. Fixed-height grids (Month tiles, the time grid) cap text growth at 1.15 (`CappedFontScale`), because at 2.0 their day numbers and chips were cut off; the day page and the Schedule still show the full size. The day-number circles grow with their text.
- **Spoken names:** a Month tile says the full date and how many events ("Monday, October 26, 2026, 1 event"); a time-grid block says title, time and place; an empty hour says "New event" as its action; the repeat "−" and "+" buttons say "Fewer" and "More" (Greek: "Μείωση", "Αύξηση"). Icons already had names.
- **Touch targets:** buttons, chips, switches and list rows are 48 dp or more. Known smaller targets, kept because the grids are dense by design: the all-day strip pills in Day, 3 days and Week (20 dp high) and the event chips in Month (they open the day page, which has full-size cards; the whole tile is the target, not the chip).
- **Not done:** a screen reader pass on a real phone (TalkBack), a high-contrast mode, reduced-motion handling (the app has no animations beyond the system's).

## Greek review
- Every English string has a Greek one with the same placeholders (checked by script).
- Fixed: theme descriptions were English only (now translated; theme names stay as proper names), the sync status line showed English server text (now a translated message chosen from the HTTP status), the default name of a new feed was fixed English text.
- Read through all Greek strings once for wording; two small changes.
- **Not done:** a read by a native speaker on the phone itself (the owner), Greek weather wording on the phone.

## Speed
- Measured on the owner's phone (0.4.0): screen opened to events ready 495 ms (target under 1 s), last expansion 40 ms.
- Added `app/src/main/baseline-prof.txt` (wildcard rules for the app's own classes) so Android compiles the startup code ahead of time. Its effect can only be seen in a release build on the phone (Settings > Speed); no figure is claimed here.
- Not measured: swiping between weeks on the phone.

## Public repository
The repository is prepared for a public copy: private names removed from code, tests and docs, MIT `LICENSE`, `README.md` (English and Greek). The real-data tests and the live-test script read their folder from the environment variable `BOOKAH_PRIVATE_DIR`. `tools/make-public-tree.ps1` builds the clean copy with a fresh one-commit history and scans it; it never pushes.

## 1.0
Not released. Steps: The owner tries the Phase 5 build on the phone, reports, then a final build `tools\release-android.ps1 -Version 1.0.0 -Final` with his yes.
