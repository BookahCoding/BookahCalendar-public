# Credits

**BookahCalendar** is maintained by **BookahCoding**.

## Upstream / third-party

The full licence texts are shown in the app (Settings, About, Open-source licences) and are the file `android/app/src/main/res/raw/licenses.txt`.

- Hikyaku and Herald design tokens (theme colour families, Washi & Ink and the others) — MIT, Copyright (c) 2026 Giton22 (see `THIRD-PARTY-HERALD-LICENSE.txt`). The same token tables are used in Hikyaku for Windows (`THEME-REFERENCE.md` there).
- The Hokusai-inspired themes are interpretive colour sets, not measured artwork. No museum image is bundled or fetched; the icon is an original vector drawing. The twelve month pictures (Schedule, Month view, drawer, About) are AI-generated ukiyo-e style artwork made with Grok by the owner of this project and released under the repository's MIT licence (`tools/make-banners.py` crops them to `android/app/src/main/res/drawable-nodpi/banner_MM.webp`; the full-size originals are not in the repository). The six picture-based themes (Sakura, Plum & Snow, Hydrangea Rain, Moon Viewing, Fireworks, Autumn Maple) take their accents from those pictures.
- biweekly 0.6.8 (iCalendar parsing and recurrence) — BSD-2-Clause, Copyright (c) 2013-2024 Michael Angstadt. In the app, with its full notice.
- OkHttp and Okio, Kotlin and kotlinx (coroutines, serialization), AndroidX (Compose, Activity, Core, Lifecycle, DataStore, WorkManager) — Apache-2.0. In the app, with the licence text.
- Weather data by Open-Meteo.com (https://open-meteo.com) — CC BY 4.0. Fetched only when the owner has set a place in Settings; the service receives that place's coordinates and nothing else.
- Build and test only, never shipped: ical4j and lib-recur were used in a Phase 0 comparison (spike code, not part of the app); MockWebServer (tests).
