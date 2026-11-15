# BookahCalendar design spec (draft 1, 15 Nov 2026)

Status: **approved by the owner on 15 Nov 2026** (with the Month decisions in 2.2). Changes after this need his yes. Two parts that never mix:
**Structure** comes from the owner's Google Calendar screenshots (dark mode, Xiaomi 15T). **Skin** comes from the Hikyaku for Windows theme families and our own ukiyo-e artwork. Screenshot colours and artwork are ignored. The screenshots show real events and stay out of Git; this spec uses generated example events only.

## 0. How the measurements were taken
- 23 screenshots, 1280 x 2772 px. The owner gave the phone's smallest width as **393 dp** (15 Nov), so the scale is 1280 / 393 = **3.26 px per dp**. I read positions by eye from the images and assumed 3.2 before; the difference is about 2%. Treat every dp number below as about plus or minus 5%; exact values are settled when each screen is built and compared on the phone.
- Craft Anything 0.6.1 (checked 15 Nov: checksum and code signature verified) studies live websites, apps and games; it has no tool to measure still screenshots. So I measured the screenshots myself. At the owner's word it was **deleted** the same day (nothing installed system-wide).

## 1. Deliberate deviations from the screenshots
- Week starts on **Monday** (Settings can change it). The screenshots show Sunday.
- No Tasks button, no Task or Birthday creation (Tasks are out of version 1; birthdays come from Contacts).
- No account avatar (nothing in the app needs it); the right end of the top bar gets the Today button and the view/search actions only.
- No Google event photo illustrations. Our art goes in the Schedule month banners, the drawer header, empty states and the About page.
- No invitations, "Add people", "View schedules", video conferencing, "Invite via link", Drive attachments, Gmail events.
- The drawer header carries our name and artwork, not a wordmark.
- Google events are read-only: they show a lock mark and the edit pencil is hidden.
- Greek and English UI; 24-hour clock; Europe/Athens. "Use device time zone" is not offered in version 1 (always the device zone; events keep their own zone).

## 2. Structure

### 2.1 Global
- **Top app bar** (about 56 dp tall, on Surface): menu button; **month title** with a dropdown arrow (opens a month picker); search; **Today** (calendar icon showing today's date number). Greek month names are long, so the title may shorten (as in the screenshots, "Novemb...") but never push the icons off.
- **Floating "+"**: rounded square about 56 dp, corner radius about 16 dp, bottom right with a margin of about 16 dp from the edge and above the system bar. It scrolls with nothing; it stays. Tapping opens the new-event form directly (Google offers Event / Task / Birthday pills; we have only Event, so no menu).
- **Today** is a filled round pill behind its date number in every view; today's weekday label is coloured.
- **Calendar colour** is user data (Nextcloud sets it). Chips and dots show it exactly.
- Edge to edge: status and gesture bars are transparent over our surfaces.

### 2.2 Month
- 7 columns, 6 rows filling the screen height between the weekday header and the bottom. Each day is a **rounded tile** (radius about 12 dp) with a 2 to 3 dp gap; date number at the top centre; days of the other months keep the same tile.
- Event chip: height about 15 dp, radius about 4 dp, filled with the calendar colour, one line of 11 sp text, truncated. Up to 3 to 4 chips fit per tile; if more, the last line shows "..." (the exact "+N" form is not in the screenshots, so ask the owner).
- **Tap a day: opens the Day view for that day; Back returns to Month** (the owner, 15 Nov). The Day view opened this way shows a back arrow instead of the menu button.
- **Month moves between months horizontally** (swipe left or right), like the other views (the owner, 15 Nov).
- **Too many events in a day: the last line shows "+N"** (N = events not shown) instead of the "..." Google uses (the owner, 15 Nov).

### 2.3 Week, 3 days, Day (one grid component)
- Header: weekday letters (about 12 sp) over date numbers (about 18 sp); today's number in the filled pill. In landscape the month abbreviation sits at the far left of the header.
- **All-day strip** between the header and the grid: chips about 20 dp tall; multi-day events span columns.
- **Time grid:** hour labels on the left (column about 56 to 64 dp, 11 sp, "05:00" format), every hour is a rounded tile (radius about 8 dp) about 32 dp tall per hour in portrait. Events are rounded rectangles (radius about 6 dp) inside the tiles; overlapping events share the width; very short events still show one line (a setting exists in Google to size them like 30-minute ones; we copy that switch, off by default).
- **Now line:** a dot at the left end and a thin line across today's column.
- Horizontal swipe moves by one period (a week in Week view, 3 days in 3-day view, a day in Day view); pinch changes the hour height (plan, section 6.2). Both are plan decisions, not visible in the screenshots: The owner confirms them on the phone when Phase 2 is tested.
- Tapping an empty hour starts **quick create**: the slot is outlined with two drag handles (resize) and a bottom sheet shows "Add title", the date and time summary and **Save**; the keyboard opens at once.

### 2.4 Schedule
- A vertical list. Each day: weekday letters and date number in a left column (about 56 dp wide), then full-width rounded event cards (radius about 16 dp, about 52 dp tall with 10 dp gaps, left inset about 64 dp, right margin about 16 dp). A card shows the title (about 16 sp) and the time range below it (about 14 sp); a place appears as a third line when there is one.
- A day with no events shows our own line, "A quiet day. Tap to add something.", beside the date (Greek text later); tapping creates an event on that day. Google's wording is not reused.
- A small label per week ("Nov 15 to 21") and, at the start of each month, our **month banner** (own artwork, see skin).
- Today's date is in the filled pill. The list scrolls both ways forever (lazy).

### 2.5 Drawer
- Slides in from the left, about 80% of the width, on SidebarSurface. Header: our name and a small artwork strip.
- View switcher: Schedule, Day, 3 days, Week, Month, each with an icon; the current view sits in a filled pill (about 56 dp tall, fully rounded). Then **Refresh**.
- Calendar list: one **checkbox in the calendar's colour** per calendar; unchecked hides it. Grouped by source (Nextcloud, then Google read-only feeds); the Contact birthdays calendar is in the list and **hidden by default** (the owner can flip it). A footer link opens Settings.

### 2.6 Event sheet (view)
- Full-height sheet: close (X) at the left, edit pencil and "..." at the right (the pencil is hidden on read-only events and a lock is shown).
- Colour square, large title (about 28 sp), a date and time line (example: "Tue, 10:00 to 11:00"), the repeat summary in words (example: "Repeats weekly on Tuesday"), then rows with a leading icon: reminders, calendar name, location (tap opens maps), notes.
- "..." offers Delete, Duplicate (later), Share as .ics (later).

### 2.7 Edit event (form)
- Close (X) at the left, **Save** as a tonal pill at the right, a drag handle at the top. Large title field. A row of **calendar chips** (the chosen one filled), only for writable Nextcloud calendars.
- **All-day** switch; start row and end row (date at the left, time at the right; tapping opens the pickers, whose screenshots are still missing); the time-zone row (Eastern European Time); the **repeat** row ("Does not repeat" or the summary); location; reminders (each with an X, plus "Add notification"); colour ("Default colour" or a swatch); notes.
- Row height about 48 dp, a 1 dp divider between groups, the left icon column about 64 dp wide, a 16 dp right margin.
- **Repeat row** opens a rounded menu card with radio buttons: Does not repeat, Every day, Every week, Every month, Every year, Custom (the custom screen's screenshot is still missing). The row's summary is in words.
- **Reminder row:** a menu card with presets (ours: At the time, 5, 10, 15, 30 minutes, 1 hour, 1 day) and Custom: a number plus Minutes / Hours / Days / Weeks, notification only (no "as email"). Each reminder has an X; "Add notification" adds one.
- **Date picker** is a dialog with the large date, month and year dropdown, previous/next arrows, the month grid (Monday first for us), a typing mode (pencil), Cancel and OK. **Time picker** is a dialog with two large boxes (hours selected first), a 24-hour dial (outer 0 to 11, inner 12 to 23) and a keyboard toggle, Cancel and OK. Both sit on the partial-layer surface (see 3.2).
- **Saving or deleting a repeating event** asks "This event / This and following events / All events" in a dialog with Cancel and Save (or Delete); "This event" is preselected, as in the screenshots.
- Drag handle at the top of the form (the form is a sheet that can be pulled down to cancel; with unsaved changes it asks first).

### 2.8 Settings
- Top level: **General**, then **Calendars** grouped by source with a colour dot each (tap one for its own settings: name, colour, default reminders, show/hide). Version 1 General rows: Start of the week; Show week number; Display shorter entries the same size as 30-minute entries; Default event duration; Default reminder; Theme (the 11 families) and Light / Dark / System; Notifications (open the system page); Updates; About.
- Row style: 56 to 72 dp tall, label and a muted value line below, switches on the right, thin dividers between groups, a muted section header ("Notifications").

## 3. Skin

### 3.1 Themes
- The 11 Hikyaku for Windows families, each with Light and Dark: Washi & Ink (**default**), Great Wave, Red Fuji, Pine & Mist, Chrysanthemums, Peonies, Kirifuri Falls, Thunderstorm, Irises, Shibuya Night, Akihabara. Token values are copied from `ThemeCatalog.cs` (MIT, see `CREDITS.md`) into one Kotlin theme file when Phase 1 starts. Appearance: Light / Dark / System; the theme picker is a gallery like Hikyaku's, with a live preview.
- Hikyaku's rule applies: **neutral major surfaces** (off-white or black) plus **two accents**. Primary accent for the main action; secondary accent for selection and switches.

### 3.2 Mapping Google's layers onto Hikyaku's surfaces
Google's dark mode puts the grid tiles *darker* than the page; Hikyaku's dark page is pure black, so the layering is **reversed**: tiles are one step lighter than the page.

| Role | Dark | Light |
|---|---|---|
| Screen background | Bg `#000000` | Bg `#F7F7F5` |
| Top app bar, sheets, forms | Surface | Surface |
| Drawer | SurfaceSidebar `#080808` | SurfaceSidebar `#F0F0EE` |
| Month tiles and time-grid hour tiles | SurfaceRaised `#171717` | SurfaceRaised `#F3F3F1` plus a 1 dp Border hairline |
| **Partial sheets** (quick create, calendar picker), **dialogs, menus, pickers** | Surface3 `#202020`, with the theme Border as a top edge and the usual screen scrim | Surface `#FAFAF8` with a Border top edge and the scrim |
| Selected-state chips on those layers, disabled fields | Surface3 / Well | Well |
| Text fields, wells | Well | Well |
| Today pill, FAB, Save pill, now line | Accent fill, OnAccent label | same |
| Selected drawer item, switch on, selected chip | Secondary (a 20% tint behind text, full colour for the switch track) | same |
| Danger (delete), warning | theme Danger / Warning | same |

Measured contrast for Washi & Ink (WCAG ratio, computed 15 Nov): text on Bg 17.96 dark and 13.70 light; muted text on SurfaceRaised 6.89 and 5.62; Accent on Bg 8.74 and 5.81; OnAccent on Accent 8.24 and 6.23; OnSecondary on Secondary 9.09 and 7.29. **Partial sheets and menus** (Surface3 in dark, Surface in light): text 13.94 and 14.06, muted text 6.26 and 5.97, Accent 6.78 and 5.96. A dark sheet on Surface (pure black) would sit *below* the `#171717` tiles behind it and read as a hole, which is why partial layers use Surface3 in dark. **Tile edge:** SurfaceRaised on Bg is only 1.17 in dark and **1.04 in light**, so in light mode tiles are told apart by the gap plus a hairline Border (a tile is not a control, so 3:1 is not required, but it must be visible). All other themes must pass the same checks in a unit test before they ship.

### 3.3 Event chips (calendar colours are data)
- The fill is the calendar's own colour exactly. The text is **black or white, whichever has the higher contrast with that fill**, independent of the theme. If neither reaches 4.5:1, nudge the fill 10 to 15% toward black or white until one does (the Nextcloud colour itself is never changed in the data).
- Examples computed on 15 Nov: Personal `#00679E` takes white (6.12:1); Birthdays `#E9D859` takes black (14.43:1); a mid-grey `#7A7A7A` takes black (4.89:1, white would be 4.29).
- A multi-calendar overlay never blends colours. Google (read-only) events get a small lock glyph on the chip and a hairline outline.

### 3.4 Artwork (original, drawn in code)
- Only here: **Schedule month banners** (12 seasonal ukiyo-e motifs, one per month, shown at the start of each month in the Schedule), the **drawer header strip**, **empty states** (the "quiet day", empty search, no calendars), the About screen, and the icon. Never behind event text, in the time grid, or under any text that must be read.
- Style: ukiyo-e woodblock look in flat colour shapes: great-wave crest with foam claws, Mount Fuji with a snowcap, a sun disc, pines, irises, cranes, reeds; tinted from the current theme's two accents plus neutral paper or ink so every theme gets its own banners. Drawn as vector shapes in code (like `tools/make-icon.py`): no museum scans, no downloads, no licence questions, a tiny app size.
- Banner height about 120 dp with a 16 dp radius; it carries the month name as readable text on a scrim of the theme's Surface colour, never on the busy part of the drawing.
- Reduced motion: no animation in artwork; high contrast mode drops the artwork and keeps the label.

### 3.5 Icon
Chosen draft **B** (a great wave and a vermilion sun on a calendar page, date seal in the wave; vermilion rounded square like Hikyaku's icon). To finish in Phase 1: outlined day number, adaptive layers (foreground inside the 66/108 safe circle, background, monochrome), and a check at 48 px.

### 3.6 Type and shape
- UI font: the system font (the app must read Greek well); Hikyaku's Geist is an option to test later. Sizes: title 28 sp, large title 22 sp, body 16 sp, secondary 14 sp, labels and chips 11 to 12 sp. Text scales with the system font size; chips and the grid reflow and never clip.
- Corner radii follow the screenshots (4, 6, 8, 12, 16, 28 dp and full round). Touch targets are at least 48 dp.
- Motion: 180 ms panel transitions as in Hikyaku; zero under reduced motion.

## 4. Not specified yet (waiting for screenshots or a decision)
The custom repeat screen (weekly on chosen days, every N, end date or count), search (empty and with results), a calendar's own settings. None of them is needed for Phase 1 (read-only app); they are needed from Phase 3 (custom repeat: Phase 4).
