# BookahCalendar

A native Android calendar app (Kotlin, Jetpack Compose) for people who keep their calendars in **Nextcloud**. It shows your Nextcloud calendars with full editing over CalDAV, and can show other calendars (for example Google Calendar) **read-only** from their secret iCal address, without any Google login.

English and Greek. Greek: see [README.el.md](README.el.md).

## What it does
- Schedule, Month, Day, 3 days and Week views, with search and a weather line above each coming day (optional).
- Create, edit and delete events, including repeating events ("this event / this and following / all events") and a repeat editor for new events.
- Phone reminders as exact alarms with Open and Snooze, restored after a restart.
- Works from a saved copy when offline; refreshes in the background.
- 11 colour themes, light, dark or system appearance, large-text and screen-reader support.

## What it does not do
Events with other people (organiser or attendees) can be deleted after a warning but never edited, because a change could make the server send e-mails to others. There is no Google account sign-in, no analytics, no crash upload.

## Privacy
The app contacts only your Nextcloud server, the calendar feed addresses you add, GitHub only when you tap "Check for updates" (released builds; nothing about you is sent), and, only if you type a place in Settings for the weather, Open-Meteo (it receives that place's coordinates and nothing else). Your app password and feed addresses are stored encrypted with the Android Keystore.

## Build
Requirements: Android Studio's bundled JDK, Android SDK, Gradle (the wrapper is included).

```
cd android
gradlew.bat :core-data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

The tests use generated events only. Some extra tests read real calendar files from a folder named by the environment variable `BOOKAH_PRIVATE_DIR` and skip themselves when it is not set. `tools/release-android.ps1` builds, checks and (only when told to) publishes a signed release; it needs your own signing key in `android/keystore.properties` (not part of the repository).

## Layout
`android/` the app (`app`, `core-data`, `design`), `docs/` designs and specs, `tools/` helper scripts.

## Licence
MIT, see [LICENSE](LICENSE). Third-party notices: [docs/CREDITS.md](docs/CREDITS.md) and the in-app licence list. The month artwork and icon are original drawings.
