# Phase 1 design: read-only app (15 Nov 2026)

Goal (plan section 9): a signed, installable app that signs in to Nextcloud, syncs the calendars, caches them for offline, shows **Schedule** and **Month**, lists calendars with colours and show/hide, shows Google read-only, has Settings (account, themes, feeds), and can update itself from GitHub releases (off while the repo is private). No editing, no reminders yet (Phases 3 and 4). Structure and skin follow the approved `docs/design-spec.md`.

## 1. Decisions and how they were made
| Topic | Decision | Why |
|---|---|---|
| Package and name | `dev.bookah.calendar`, "BookahCalendar" | like BookahPhotos |
| Stack | Kotlin 2.4.20, AGP 9.4.1, Gradle 9.8.0, Compose BOM 2026.09.00, compileSdk 37, minSdk 31, arm64 only, OkHttp 5.5.1, kotlinx.serialization, DataStore, coroutines | copied from BookahPhotos, known to build here |
| Modules | `app` (screens, navigation, workers), `core-data` (CalDAV, iCalendar, cache, sync, session), `design` (theme families, art, chip colours) | small first; feature modules only when a screen group grows |
| Local cache | **Plain SQLite** (`SQLiteOpenHelper`), not Room | no annotation processor: avoids a KSP/Kotlin 2.4 compatibility risk; the data is two tables |
| Sign-in | **The owner types the Nextcloud address, user name and an app password into hidden fields** (his own wording and the plan's rule). He should make a separate app password for the phone, so it can be revoked on its own. **Login Flow v2** (browser approves, the app receives a new app token) is ported but stays off unless the owner says yes, because it creates a token in his Nextcloud. Server address never in the repo | The owner's rule: "typed by me into a hidden input"; no writes in Phase 1 |
| Secrets | Keystore AES-GCM key encrypts the app password and Google feed addresses in DataStore | BookahPhotos `KeystoreSessionStore` |
| Recurrence | **biweekly 0.6.8** (parse and expand), ported from the Phase 0 spike (`spike/recurrence`) | 1,112 of 1,112 instances equal to Nextcloud |
| Sync | CalDAV `sync-collection` token per calendar, then `calendar-multiget` for changed hrefs, 50 per request; 403/409 on a token means full resync; paths always taken from the server's answer (user id case differs from the login) | tested in Phase 0 (about 1 s per request) |
| Google | list of read-only iCal feeds (one in the UI now, the model allows more); `GET` with `If-None-Match`; address stored encrypted; expanded on the phone; a lock mark on chips | The owner may add a second feed later |
| Google duplicates | **A Google instance is hidden when a Nextcloud event has the same UID** (for edited instances, the same UID and RECURRENCE-ID). Settings shows how many are hidden | Phase 1 check on 15 Nov: all 345 Google UIDs and all 362 UID+RECURRENCE-ID pairs already exist in Personal (the 14 Nov copy), so without this rule every Google event would show twice. Google changes to already-copied events are ignored (Nextcloud is the source of truth) |
| Calendar flags | each calendar has `editable`: false for Contact birthdays (Nextcloud rebuilds it from Contacts), false for Google | Phase 0 reported it as writable; saves a Phase 3 bug |
| Contact birthdays | a normal calendar, **hidden by default** | The owner, 14 Nov |
| Day view in Phase 1 | tapping a Month day opens a simple **day list page** (events of that day, Back returns to Month); the real time-grid Day view replaces it in Phase 2 | The owner asked for tap-to-Day with Back to Month; the grid is Phase 2 |
| Languages | English and Greek strings from the start (`values/`, `values-el/`) | plan section 2 |
| Updater | `UpdateChecker` ported; off unless `-PreleasesRepo` is set (it needs a public repo) | like BookahPhotos |
| Signing | own key in `<private folder>\signing\`, `android/keystore.properties` untracked, certificate SHA-256 committed | like BookahPhotos; the owner must back the folder up |

**Changes to the plan, recorded here and in the plan (no silent substitution):** plain SQLite replaces Room; the plan's "pre-expanded instances for the visible range" cache is dropped for now. Build A measures cold start to a filled Schedule on the emulator with about 800 generated events; if parsing pushes it past 1 s, an instances table for a rolling window is added.

**Checks added by the advisor:** (1) a JVM test, run only when the private Phase 0 files exist, ports the expander and must reproduce 1,112 of 1,112 server-expanded instances (counts only; skipped on a public checkout; no real data enters the repo). (2) A **minified (R8) release build** is part of Build A, since biweekly's optional Jackson/XML paths often break R8; rules are added only when an error names a class. (3) Emulator tests use an own AVD, `BookahCalendarTest`, made from the already installed system image; the running `emulator-5554` is left alone.

## 2. Data flow
```
Login Flow v2 -> Session(server, user, appPassword) -> Keystore store
Sync (open, pull-to-refresh, WorkManager every 15 min while on the tailnet):
  PROPFIND calendar home (Depth 1) -> calendars (href, name, colour, privileges, sync-token)
  per calendar: REPORT sync-collection(token) -> changed/removed hrefs -> REPORT calendar-multiget -> upsert/delete rows (raw ICS + etag)
Google feeds: GET (If-None-Match) -> ICS blob in filesDir (+ etag, fetched-at)
Read path: Expander(range) over cached ICS -> Instances (uid, start, end, allDay, calendar, readOnly) -> Schedule / Month
```
- Expansion runs off the main thread for the visible range plus a margin; results are kept per range and dropped when the cache changes.
- A failed sync never deletes cached data. The UI shows a quiet "offline" or "last synced" line.
- All HTTP is HTTPS to the Nextcloud address; the only other hosts are the Google feed address and GitHub (when the updater is on). User-Agent has no personal data.

## 3. Screens built in Phase 1
Sign-in; Schedule (weeks, day labels, cards, "quiet day" lines, month banner); Month (tiles, chips, +N, tap day, horizontal month swipe); Day list page (placeholder for Phase 2); drawer (view switcher with Schedule and Month enabled and the others marked "soon"; calendar checkboxes grouped by source; Refresh; Settings); Settings (account and sign out, Google feeds add/remove with hidden input, week start, theme gallery of 11 families with Light/Dark/System, updates, About with credits, crash log); event sheet (read-only: title, time, repeat summary, place, notes, reminders as text, calendar, lock for Google).

## 4. Milestones (each ends with tests and a pre-release for the owner)
1. **Build A (0.1.0):** project, modules, theme families with contrast tests, sign-in, calendar list, sync, SQLite cache, expander, Schedule with real data.
2. **Build B (0.2.0):** Month, Day list page, drawer with show/hide, Google feeds, event sheet.
3. **Build C (0.3.0):** background refresh, offline state, banner and header art, Greek strings, Settings complete, updater (off), release script, crash log, speed numbers.
Each build: JVM tests, emulator tests on generated data only, a minified build tried on the emulator, and an honest "not verified on the phone" list.

## 5. Targets (plan section 8, measured on the Xiaomi in Settings > Speed like BookahPhotos)
Cold start to filled Schedule from cache under 1 s; month swipe without dropped frames; web-UI change visible after pull-to-refresh and within 15 minutes in the background (to verify on HyperOS).

## 6. Needs the owner's yes
- Creating the release signing key (he must back the folder up).
- Each pre-release publish (or a standing yes like BookahPhotos).
- Any install on the phone beyond the spike.
- Anything that writes to Nextcloud or Google: **none in Phase 1** (the app is read-only; sign-in creates an app password, which he can revoke).

## 7. Risks
- biweekly on Android (R8, time zones): tested early on the emulator with a minified build.
- HyperOS background limits: measured, not promised.
- Greek text wrapping in chips and the grid.
- Android Keystore data lost on restore: the session reads as empty and the owner signs in again.
