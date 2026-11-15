package dev.bookah.calendar.data

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The real write path against the owner's real Nextcloud, but ONLY inside a throw-away calendar that this test creates and deletes
 * (never Personal). Runs only when `tools\live-write-test.ps1` has put the connection details into environment variables (read from the
 * Windows Credential Manager, never typed or printed); on any other run it is skipped. One request at a time. It prints step names and
 * HTTP status codes only: no address, no user, no titles, no hrefs. See docs/phase-3-design.md section 2 rule 7 and 2b point 6.
 */
class LiveWriteTest {
    private val server = System.getenv("BOOKAH_LIVE_SERVER")
    private val user = System.getenv("BOOKAH_LIVE_USER")
    private val pass = System.getenv("BOOKAH_LIVE_PASS")

    private val http = Http.newClient()
    private val xml = "application/xml; charset=utf-8".toMediaType()

    private fun log(step: String, detail: String = "") = println("LIVE $step${if (detail.isEmpty()) "" else ": $detail"}")

    /** A raw request for the things the app itself never does (making and removing the test calendar, cleaning the trash). Returns the status. */
    private fun raw(method: String, path: String, body: String? = null, depth: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val base = server!!.trimEnd('/')
        val req = Request.Builder().url(base + path)
            .method(method, body?.toRequestBody(xml))
            .header("Authorization", okhttp3.Credentials.basic(user!!, pass!!, Charsets.UTF_8))
            .header("User-Agent", Http.USER_AGENT)
            .apply { if (depth != null) header("Depth", depth); headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(req).execute().use { return it.code to it.body.string() }
    }

    @Test fun writeCycleInAThrowawayCalendar() = runBlocking {
        assumeTrue("live connection details not provided: skipped", !server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        val marker = "bookahtest-" + UUID.randomUUID().toString().take(8)
        val session = Session(server!!, user!!, pass!!)
        val client = CalDavClient(http, session)
        val home = "/remote.php/dav/calendars/${session.user}/"
        var calendarHref: String? = null
        val uids = ArrayList<String>()
        try {
            // 1. the throw-away calendar
            val (made, _) = raw(
                "MKCALENDAR", "$home$marker/",
                """<?xml version="1.0"?><c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:set><d:prop><d:displayname>$marker</d:displayname>
                   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>""",
            )
            log("make test calendar", "HTTP $made")
            assertEquals(201, made)
            val remote = client.listCalendars().first { it.name == marker }
            calendarHref = remote.href
            assertTrue("the test calendar must be writable", remote.canWrite)

            val store = MemoryCalendarStore()
            val cal = store.replaceNextcloudCalendars(listOf(remote)).first()
            var n = 0
            val writer = EventWriter(store, client, newUid = { (marker + "-" + (++n)).also { uids.add(it) } })
            val start = LocalDate.now().plusDays(3).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            val draft = EventDraft("Live test event", EventWhen.Timed(start, start + 3_600_000L), "Live place", "Live notes, with; marks", listOf(15))

            // 2. create
            val created = writer.create(cal.id, draft)
            log("create", created::class.simpleName.orEmpty())
            assertTrue(created is WriteResult.Saved)
            val href = (created as WriteResult.Saved).href
            val onServer = client.get(href)
            assertNotNull(onServer)
            assertEquals(onServer!!.etag, store.objectByHref(href)!!.etag)                 // our copy has the server's own ETag
            assertTrue("SUMMARY:Live test event" in onServer.ics)
            log("created event read back", "ETag matches, text kept")

            // 3. edit
            val edited = writer.update(href, EventEdit(title = "Live test event 2", location = "Live place 2"))
            log("edit", edited::class.simpleName.orEmpty())
            assertTrue(edited is WriteResult.Saved)
            assertTrue("SUMMARY:Live test event 2" in client.get(href)!!.ics)

            // 4. a refused write: an old ETag must give 412, never an overwrite
            val stale = try { client.put(href, client.get(href)!!.ics, ifMatch = "definitely-not-the-etag"); "accepted" } catch (e: PreconditionFailedException) { "412" }
            log("write with a wrong ETag", stale)
            assertEquals("412", stale)

            // 5. conflict: change it "elsewhere", then edit here
            val theirsText = client.get(href)!!.ics.replace("SUMMARY:Live test event 2", "SUMMARY:Live test event changed elsewhere")
            client.put(href, theirsText, ifMatch = client.get(href)!!.etag)
            val conflict = writer.update(href, EventEdit(notes = "Mine"))
            log("edit after a change elsewhere", conflict::class.simpleName.orEmpty())
            assertTrue(conflict is WriteResult.Conflict)
            assertTrue("Live test event changed elsewhere" in client.get(href)!!.ics && "Mine" !in client.get(href)!!.ics)   // nothing was written
            val merged = writer.updateOnTheirs(href, EventEdit(notes = "Mine"))
            log("add my changes on top", merged::class.simpleName.orEmpty())
            assertTrue(merged is WriteResult.Saved)
            val after = client.get(href)!!.ics
            assertTrue("changed elsewhere" in after && "DESCRIPTION:Mine" in after)

            // 6. delete
            val deleted = writer.delete(href)
            log("delete", deleted::class.simpleName.orEmpty())
            assertTrue(deleted is WriteResult.Saved)
            assertNull(client.get(href))
        } finally {
            // clean-up, always. A normal DELETE of a calendar only moves it to Nextcloud's trash (it then stays in the calendar home with a
            // "deleted-calendar" mark, and shows under "Deleted items" in the Calendar app). The test calendar is removed for good with the
            // header the Calendar app itself uses for "delete permanently": X-NC-CalDAV-No-Trashbin. The events inside go with it.
            val h = calendarHref ?: "$home$marker/"
            val (deleteStatus, _) = raw("DELETE", h, headers = mapOf("X-NC-CalDAV-No-Trashbin" to "1"))
            log("delete test calendar for good", "HTTP $deleteStatus")
            // look at the raw calendar home, not at the app's calendar list: the raw one also shows soft-deleted calendars
            val (_, homeListing) = raw("PROPFIND", home, """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>""", depth = "1")
            val left = Regex(marker).findAll(homeListing).count()
            log("entries of this test left in the calendar home", left.toString())
            assertTrue("permanent delete must succeed or the calendar was never made", deleteStatus == 204 || deleteStatus == 404)
            assertEquals("nothing of the test may be left (not even in the trash)", 0, left)
        }
    }
}
