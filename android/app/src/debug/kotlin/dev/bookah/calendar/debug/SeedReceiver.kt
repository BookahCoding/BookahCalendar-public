package dev.bookah.calendar.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.calendar.BookahApp
import dev.bookah.calendar.data.FeedCalendar
import dev.bookah.calendar.data.FeedConfig
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
            ),
        )
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

/** A long description (about 60 sentences, folded like a real iCal line) to test scrolling of the event page. */
private fun longDescription(): String = "DESCRIPTION:" + (1..60).joinToString(" ") { "Generated sentence $it of a long description used to test scrolling." }.chunked(70).joinToString("\r\n ") + "\r\n"
