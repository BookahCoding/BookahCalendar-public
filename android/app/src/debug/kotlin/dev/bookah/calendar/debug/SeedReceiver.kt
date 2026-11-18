package dev.bookah.calendar.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.calendar.BookahApp
import dev.bookah.calendar.data.FeedCalendar
import dev.bookah.calendar.data.FeedConfig
import dev.bookah.calendar.data.ListKind
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.feedHref
import dev.bookah.calendar.data.RemoteCalendar
import dev.bookah.calendar.data.RemoteObject
import dev.bookah.calendar.data.Session
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `adb shell am broadcast -a dev.bookah.calendar.SEED -n dev.bookah.calendar/.debug.SeedReceiver --ei count 800`
 * Replaces the cache with generated events (3 calendars, about 5% repeating) and a fake session, so the app opens straight
 * on the Schedule. Syncing then fails (the fake server does not exist), which also tests the offline path: cached data stays.
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // `am broadcast -a dev.bookah.calendar.TOUCH --es title "<exact title>"`: change that event on the fake server only (no re-seeding)
        if (intent.action == "dev.bookah.calendar.TOUCH") { intent.getStringExtra("title")?.let { FakeServer.current?.touch(it) }; return }
        val count = intent.getIntExtra("count", 800)
        val graph = (context.applicationContext as BookahApp).graph
        runBlocking { graph.sessions.save(Session("https://calendar.invalid", "tester", "not-a-real-password")) }
        graph.store.clearAll()
        val cals = graph.store.replaceNextcloudCalendars(
            listOf(
                RemoteCalendar("/cal/a/", "Generated A", "#00679E", true),
                RemoteCalendar("/cal/b/", "Generated B", "#E9D859", true),
                RemoteCalendar("/cal/c/", "Generated C", "#7A7A7A", true),
                // a task-only list (what the Nextcloud Tasks app makes) and a list that takes both events and tasks
                RemoteCalendar("/cal/tasks/", "Generated tasks", "#3E8E41", true, kind = ListKind.Tasks),
                RemoteCalendar("/cal/mixed/", "Generated mixed", "#C0504D", true, kind = ListKind.Both),
            ),
        ).filter { it.kind == ListKind.Events }
        seedTasks(graph)
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val today = LocalDate.now()
        val objects = HashMap<Long, MutableList<RemoteObject>>()
        for (i in 0 until count) {
            val cal = cals[i % cals.size]
            val day = today.plusDays((i * 7 % 240 - 30).toLong())
            val start = day.atTime(6 + i % 14, (i % 4) * 15).toInstant(ZoneOffset.UTC)
            val end = start.plusSeconds(1800L + (i % 5) * 900L)
            val rrule = (if (i % 20 == 0) "RRULE:FREQ=WEEKLY;COUNT=12\r\n" else "") + (if (i % 33 == 7) "ORGANIZER:mailto:someone@example.org\r\n" else "")
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VEVENT\r\nUID:gen-$i@invalid\r\n" +
                "DTSTAMP:20260101T000000Z\r\nDTSTART:${fmt.format(start.atOffset(ZoneOffset.UTC))}\r\nDTEND:${fmt.format(end.atOffset(ZoneOffset.UTC))}\r\n" +
                "SUMMARY:Generated event $i\r\n${if (i % 3 == 0) "LOCATION:Room ${i % 9}\r\n" else ""}${if (i % 10 == 5) longDescription() else ""}$rrule" +
                "END:VEVENT\r\nEND:VCALENDAR\r\n"
            objects.getOrPut(cal.id) { ArrayList() }.add(RemoteObject("${cal.href}gen-$i.ics", "e$i", ics))
        }
        // one easy-to-find repeating event for UI tests: weekly, 8 times, from tomorrow 09:00 UTC, no people
        val seriesStart = today.plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC)
        val seriesIcs = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VEVENT\r\nUID:series-test@invalid\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART:${fmt.format(seriesStart.atOffset(ZoneOffset.UTC))}\r\nDTEND:${fmt.format(seriesStart.plusSeconds(3600).atOffset(ZoneOffset.UTC))}\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=8\r\nSUMMARY:Series test\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        objects.getOrPut(cals[0].id) { ArrayList() }.add(RemoteObject("${cals[0].href}series-test.ics", "s1", seriesIcs))
        // an event that starts in 4 minutes with a reminder 2 minutes before it: the notification must appear in about 2 minutes
        val soon = java.time.Instant.now().plusSeconds(240)
        val soonIcs = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VEVENT\r\nUID:reminder-test@invalid\r\nDTSTAMP:20260101T000000Z\r\n" +
            "DTSTART:${fmt.format(soon.atOffset(ZoneOffset.UTC))}\r\nDTEND:${fmt.format(soon.plusSeconds(1800).atOffset(ZoneOffset.UTC))}\r\nSUMMARY:Reminder test\r\nLOCATION:Room 1\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:r\r\nTRIGGER:-PT2M\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        objects.getOrPut(cals[0].id) { ArrayList() }.add(RemoteObject("${cals[0].href}reminder-test.ics", "r1", soonIcs))
        for ((id, list) in objects) graph.store.upsertObjects(id, list)

        // a generated read-only feed: 30 events of its own plus 10 copies of Nextcloud events (same UID), which must stay hidden.
        // Its address does not exist, so a refresh reports "offline" for it and keeps these events.
        val feed = FeedConfig("seedfeed", "Generated feed", "https://feed.invalid/generated.ics", "#7A5CA8")
        runBlocking { graph.feeds.save(listOf(feed)) }
        val feedCal = graph.store.syncFeedCalendars(listOf(FeedCalendar(feedHref(feed.id), feed.name, feed.colorHex))).first { it.source == Source.Google }
        val feedObjects = ArrayList<RemoteObject>()
        for (i in 0 until 40) {
            val uid = if (i < 10) "gen-${i * 3 + 1}@invalid" else "feed-$i@invalid"
            val start = today.plusDays((i % 14).toLong()).atTime(7 + i % 10, 0).toInstant(ZoneOffset.UTC)
            val end = start.plusSeconds(3600)
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\n" +
                "DTSTART:${fmt.format(start.atOffset(ZoneOffset.UTC))}\r\nDTEND:${fmt.format(end.atOffset(ZoneOffset.UTC))}\r\nSUMMARY:Generated feed event $i\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            feedObjects.add(RemoteObject("${feedCal.href}/$uid", "f$i", ics))
        }
        graph.store.upsertObjects(feedCal.id, feedObjects)

        // `--ez fake true`: talk to an in-memory fake server instead of the (non-existent) real one, so create, edit, delete and sync can be tried
        // end to end. `--es touch <title>`: change that event on the fake server behind the app's back (to try the conflict dialog).
        val touch = intent.getStringExtra("touch")
        val fake = if (intent.getBooleanExtra("fake", false) || touch != null) FakeServer.current ?: FakeServer.from(graph.store).also { FakeServer.current = it } else null
        if (fake != null) {
            graph.apiFactory = { fake }
            touch?.let { fake.touch(it) }
        } else {
            graph.apiFactory = { dev.bookah.calendar.data.CalDavClient(graph.http, it) }
            FakeServer.current = null
        }
    }
}

/**
 * Generated tasks (VTODO) for the two task-capable lists: dated all-day, dated with a time, overdue, without a date, finished (must not show),
 * a subtask, a Greek title, one with its own reminder (must never ring here: only BookahTasks rings for tasks), the 00:00 / 23:59 forms
 * BookahTasks writes, a start-only task, plus two events in the mixed list.
 */
private fun seedTasks(graph: dev.bookah.calendar.AppGraph) {
    val all = graph.store.calendars()
    val tasks = all.first { it.kind == ListKind.Tasks }
    val mixed = all.first { it.kind == ListKind.Both }
    val zone = java.time.ZoneId.systemDefault()
    val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    val dateFmt = DateTimeFormatter.BASIC_ISO_DATE
    val today = LocalDate.now(zone)
    fun at(day: LocalDate, h: Int, m: Int) = utc.format(day.atTime(h, m).atZone(zone).withZoneSameInstant(ZoneOffset.UTC))
    fun date(day: LocalDate) = dateFmt.format(day)
    fun todo(uid: String, title: String, vararg lines: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\n" +
        "DTSTAMP:20260101T000000Z\r\nSUMMARY:$title\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VTODO\r\nEND:VCALENDAR\r\n"
    val soon = java.time.Instant.now().plusSeconds(240).atOffset(ZoneOffset.UTC)
    val taskObjects = listOf(
        "t-allday" to todo("t-allday@invalid", "Generated bill to pay", "DUE;VALUE=DATE:${date(today.plusDays(1))}", "PRIORITY:1",
            "DESCRIPTION:Generated notes for this task.\\nA second line.", "URL:https://example.org/generated"),
        "t-timed" to todo("t-timed@invalid", "Generated call", "DUE:${at(today, 15, 30)}"),
        "t-overdue" to todo("t-overdue@invalid", "Generated overdue task", "DUE;VALUE=DATE:${date(today.minusDays(2))}", "PRIORITY:5"),
        "t-undated" to todo("t-undated@invalid", "Generated task without a date", "PRIORITY:9"),
        "t-parent" to todo("t-parent@invalid", "Generated parent task"),
        "t-child" to todo("t-child@invalid", "Generated subtask", "DUE;VALUE=DATE:${date(today.plusDays(2))}", "RELATED-TO:t-parent@invalid"),
        "t-greek" to todo("t-greek@invalid", "Αγορά ψωμιού", "DUE;VALUE=DATE:${date(today)}"),
        "t-alarm" to todo("t-alarm@invalid", "Generated task with its own reminder", "DUE:${utc.format(soon)}",
            "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:r", "TRIGGER:-PT2M", "END:VALARM"),
        // how BookahTasks stores a date-only start beside a timed deadline (00:00) and a date-only deadline beside a timed start (23:59)
        "t-coerced-start" to todo("t-coerced-start@invalid", "Generated task, start at midnight", "DTSTART:${at(today.plusDays(1), 0, 0)}", "DUE:${at(today.plusDays(3), 14, 0)}"),
        "t-coerced-due" to todo("t-coerced-due@invalid", "Generated task, deadline 23:59", "DTSTART:${at(today.plusDays(2), 9, 0)}", "DUE:${at(today.plusDays(4), 23, 59)}"),
        "t-start-only" to todo("t-start-only@invalid", "Generated task, start only", "DTSTART;VALUE=DATE:${date(today.plusDays(5))}"),
        "t-done" to todo("t-done@invalid", "Generated finished task", "DUE;VALUE=DATE:${date(today)}", "STATUS:COMPLETED", "COMPLETED:20260101T000000Z"),
    ).map { (name, ics) -> RemoteObject("${tasks.href}$name.ics", "t-$name", ics) }
    graph.store.upsertObjects(tasks.id, taskObjects)
    val mixedObjects = listOf(
        RemoteObject("${mixed.href}m-undated.ics", "m1", todo("m-undated@invalid", "Generated mixed-list task without a date")),
        RemoteObject("${mixed.href}m-dated.ics", "m2", todo("m-dated@invalid", "Generated mixed-list task", "DUE:${at(today.plusDays(1), 10, 0)}")),
        // 100 percent but not completed: still open, as in BookahTasks
        RemoteObject("${mixed.href}m-full.ics", "m3", todo("m-full@invalid", "Generated task at 100 percent, still open", "DUE;VALUE=DATE:${date(today)}", "PERCENT-COMPLETE:100")),
        // overdue without a time: shows the "Late" label
        RemoteObject("${mixed.href}m-late.ics", "m5", todo("m-late@invalid", "Generated late task without a time", "DUE;VALUE=DATE:${date(today.minusDays(1))}")),
        RemoteObject(
            "${mixed.href}m-event.ics", "m4",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VEVENT\r\nUID:m-event@invalid\r\nDTSTAMP:20260101T000000Z\r\n" +
                "DTSTART:${at(today, 12, 0)}\r\nDTEND:${at(today, 13, 0)}\r\nSUMMARY:Generated mixed-list event\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n",
        ),
    )
    graph.store.upsertObjects(mixed.id, mixedObjects)
}

/** A long description (about 60 sentences, folded like a real iCal line) to test scrolling of the event page. */
private fun longDescription(): String = "DESCRIPTION:" + (1..60).joinToString(" ") { "Generated sentence $it of a long description used to test scrolling." }.chunked(70).joinToString("\r\n ") + "\r\n"
