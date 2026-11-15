# Credits

**BookahCalendar** is maintained by **BookahCoding**.

## Upstream / third-party

The full licence texts are shown in the app (Settings, About, Open-source licences) and are the file `android/app/src/main/res/raw/licenses.txt`.

- Hikyaku and Herald design tokens (theme colour families, Washi & Ink and the others) — MIT, Copyright (c) 2026 Giton22 (see `THIRD-PARTY-HERALD-LICENSE.txt`). The same token tables are used in Hikyaku for Windows (`THEME-REFERENCE.md` there).
- The Hokusai-inspired themes are interpretive colour sets, not measured artwork. No museum image is bundled or fetched; the ukiyo-e style artwork in the app (month banners, drawer strip, icon) is original vector drawing made for this project.
- biweekly 0.6.8 (iCalendar parsing and recurrence) — BSD-2-Clause, Copyright (c) 2013-2024 Michael Angstadt. In the app, with its full notice.
- OkHttp and Okio, Kotlin and kotlinx (coroutines, serialization), AndroidX (Compose, Activity, Core, Lifecycle, DataStore, WorkManager) — Apache-2.0. In the app, with the licence text.
- Weather data by Open-Meteo.com (https://open-meteo.com) — CC BY 4.0. Fetched only when the owner has set a place in Settings; the service receives that place's coordinates and nothing else.
- Build and test only, never shipped: ical4j and lib-recur were used in a Phase 0 comparison (spike code, not part of the app); MockWebServer (tests).
