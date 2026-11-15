package dev.bookah.calendar.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Repeating events against the owner's real Nextcloud, ONLY inside a throw-away calendar that this test creates and removes for good (never
 * Personal). Same rules as LiveWriteTest: runs only when `tools\live-write-test.ps1 -Series` provides the connection details, prints step names
 * and HTTP status codes only. Steps: edit this, delete this, edit this and following (two writes), delete following, delete all.
 */
class LiveSeriesTest {
    private val server = System.getenv("BOOKAH_LIVE_SERVER")
    private val user = System.getenv("BOOKAH_LIVE_USER")
    private val pass = System.getenv("BOOKAH_LIVE_PASS")
    private val http = Http.newClient()
    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val athens = ZoneId.of("Europe/Athens")

    private fun log(step: String, detail: String = "") = println("LIVE $step${if (detail.isEmpty()) "" else ": $detail"}")

    private fun raw(method: String, path: String, body: String? = null, depth: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val req = Request.Builder().url(server!!.trimEnd('/') + path).method(method, body?.toRequestBody(xml))
            .header("Authorization", okhttp3.Credentials.basic(user!!, pass!!, Charsets.UTF_8)).header("User-Agent", Http.USER_AGENT)
            .apply { if (depth != null) header("Depth", depth); headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { return it.code to it.body.string() }
    }

    private val tz = "BEGIN:VTIMEZONE\r\nTZID:Europe/Athens\r\nBEGIN:DAYLIGHT\r\nDTSTART:19700329T030000\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nTZOFFSETFROM:+0200\r\nTZOFFSETTO:+0300\r\nEND:DAYLIGHT\r\n" +
        "BEGIN:STANDARD\r\nDTSTART:19701025T040000\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nTZOFFSETFROM:+0300\r\nTZOFFSETTO:+0200\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"

    private fun starts(ics: String, fromMs: Long, toMs: Long) =
        Expander().expand(listOf(ObjectSource(1, "h", ics, "k", false)), fromMs, toMs).map { it.start }.sorted()

    @Test fun seriesCycleInAThrowawayCalendar() = runBlocking {
        assumeTrue("live connection details not provided: skipped", !server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        val marker = "bookahtest-" + UUID.randomUUID().toString().take(8)
        val session = Session(server!!, user!!, pass!!)
        val client = CalDavClient(http, session)
        val home = "/remote.php/dav/calendars/${session.user}/"
        var calendarHref: String? = null
        try {
            val (made, _) = raw(
                "MKCALENDAR", "$home$marker/",
                """<?xml version="1.0"?><c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:set><d:prop><d:displayname>$marker</d:displayname>
                   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>""",
            )
            log("make test calendar", "HTTP $made"); assertEquals(201, made)
            val remote = client.listCalendars().first { it.name == marker }
            calendarHref = remote.href
            val store = MemoryCalendarStore()
            val cal = store.replaceNextcloudCalendars(listOf(remote)).first()
            var n = 0
            val writer = EventWriter(store, client, newUid = { "$marker-split-${++n}" })

            // a weekly Monday 10:00 Athens series, 8 occurrences, starting a week from now; created by the app's own writer (repeat editor path,
            // with the generated time-zone block), so the real server's acceptance of it is tested too
            val first = LocalDate.now(athens).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
            val startMs = first.atTime(10, 0).atZone(athens).toInstant().toEpochMilli()
            val created = writer.create(cal.id, EventDraft("Live series", EventWhen.Timed(startMs, startMs + 3_600_000L), "", "", emptyList(), RepeatRule(RepeatFreq.Weekly, end = RepeatEnd.Count(8)), athens.id))
            log("create series with the repeat editor's writer", created::class.simpleName.orEmpty()); assertTrue(created is WriteResult.Saved)
            val href = store.objects(cal.id).single().href
            val stored = client.get(href)!!
            store.upsertObjects(cal.id, listOf(stored))
            assertTrue("server kept the rule and the zone", "RRULE:FREQ=WEEKLY;COUNT=8" in stored.ics && "TZID=Europe/Athens" in stored.ics)
            assertEquals(Editability.EditableSeries, SeriesEdit.editability(stored.ics))
            fun at(week: Int) = first.plusWeeks(week.toLong()).atTime(10, 0).atZone(athens).toInstant().toEpochMilli()
            val from = at(0) - 86_400_000L; val to = at(9)
            val all = starts(stored.ics, from, to)
            assertEquals(8, all.size)

            // 1. edit this occurrence only
            val r1 = writer.updateOccurrence(href, Occurrence(at(2), at(2), at(2) + 3_600_000L), Scope.This, EventEdit(title = "Live only this"))
            log("edit this occurrence", r1::class.simpleName.orEmpty()); assertTrue(r1 is WriteResult.Saved)
            val s1 = Expander().expand(listOf(ObjectSource(1, "h", client.get(href)!!.ics, "k", false)), from, to).sortedBy { it.originalStart }
            assertEquals(all, s1.map { it.start }); assertEquals(listOf("Live only this"), s1.filter { it.title != "Live series" }.map { it.title })

            // 2. delete this occurrence
            val r2 = writer.deleteOccurrence(href, at(3), Scope.This)
            log("delete this occurrence", r2::class.simpleName.orEmpty()); assertTrue(r2 is WriteResult.Saved)
            assertEquals(all - at(3), starts(client.get(href)!!.ics, from, to))

            // 3. edit this and following (a split into two stored events)
            val r3 = writer.updateOccurrence(href, Occurrence(at(5), at(5), at(5) + 3_600_000L), Scope.Following, EventEdit(title = "Live from here"))
            log("edit this and following", r3::class.simpleName.orEmpty()); assertTrue(r3 is WriteResult.Saved)
            // the writer names the later part from a generated id: find it as the one other stored object of this calendar
            val newHref = store.objects(cal.id).map { it.href }.single { it != href }
            val early = starts(client.get(href)!!.ics, from, to); val late = client.get(newHref)
            assertTrue("the later part exists on the server", late != null)
            val lateStarts = starts(late!!.ics, from, to)
            assertEquals("nothing lost or doubled", (all - at(3)), (early + lateStarts).sorted())
            log("split checked", "early ${early.size}, later ${lateStarts.size}")

            // 4. delete this and following (on the later part)
            val r4 = writer.deleteOccurrence(newHref, at(6), Scope.Following)
            log("delete this and following", r4::class.simpleName.orEmpty()); assertTrue(r4 is WriteResult.Saved)
            assertEquals(listOf(at(5)), starts(client.get(newHref)!!.ics, from, to))

            // 5. delete all
            val r5 = writer.deleteOccurrence(href, at(0), Scope.All)
            log("delete all (first object)", r5::class.simpleName.orEmpty()); assertTrue(r5 is WriteResult.Saved)
            assertNull(client.get(href))
        } finally {
            val h = calendarHref ?: "$home$marker/"
            val (deleteStatus, _) = raw("DELETE", h, headers = mapOf("X-NC-CalDAV-No-Trashbin" to "1"))
            log("delete test calendar for good", "HTTP $deleteStatus")
            val (_, homeListing) = raw("PROPFIND", home, """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>""", depth = "1")
            val left = Regex(marker).findAll(homeListing).count()
            log("entries of this test left in the calendar home", left.toString())
            assertTrue(deleteStatus == 204 || deleteStatus == 404)
            assertEquals("nothing of the test may be left", 0, left)
        }
    }
}
