package dev.bookah.calendar.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * The tick and its undo against the owner's real Nextcloud, but ONLY inside a throw-away TASK list that this test creates (MKCALENDAR with
 * VTODO as its only component) and removes for good (X-NC-CalDAV-No-Trashbin). Runs only when `tools\live-write-test.ps1 -Tasks -OwnerApproved`
 * has put the connection details into environment variables; on any other run it is skipped. One request at a time. It prints step names
 * and HTTP status codes only: no address, no user, no titles, no hrefs. NOT RUN YET: it waits for the owner's yes at that moment.
 */
class LiveTaskTest {
    private val server = System.getenv("BOOKAH_LIVE_SERVER")
    private val user = System.getenv("BOOKAH_LIVE_USER")
    private val pass = System.getenv("BOOKAH_LIVE_PASS")

    private val http = Http.newClient()
    private val xml = "application/xml; charset=utf-8".toMediaType()

    private fun log(step: String, detail: String = "") = println("LIVE $step${if (detail.isEmpty()) "" else ": $detail"}")

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

    @Test fun tickAndUndoInAThrowawayTaskList() = runBlocking {
        assumeTrue("live connection details not provided: skipped", !server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank())
        val marker = "bookahtest-" + UUID.randomUUID().toString().take(8)
        val session = Session(server!!, user!!, pass!!)
        val client = CalDavClient(http, session)
        val home = "/remote.php/dav/calendars/${session.user}/"
        var listHref: String? = null
        try {
            // 1. the throw-away task list (what the Nextcloud Tasks app makes: VTODO only)
            val (made, _) = raw(
                "MKCALENDAR", "$home$marker/",
                """<?xml version="1.0"?><c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:set><d:prop><d:displayname>$marker</d:displayname>
                   <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:set></c:mkcalendar>""",
            )
            log("make test task list", "HTTP $made")
            assertEquals(201, made)
            val remote = client.listCalendars().first { it.name == marker }
            listHref = remote.href
            assertEquals("the server must report a task-only list", ListKind.Tasks, remote.kind)
            assertTrue("the test list must be writable", remote.canWrite)
            val store = MemoryCalendarStore()
            val cal = store.replaceNextcloudCalendars(listOf(remote)).first()

            // 2. one plain task, with an alarm and an X- line that must survive (a raw create, as BookahTasks would make it)
            val href = remote.href.trimEnd('/') + "/$marker-1.ics"
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BookahCalendar live test//EN\r\nBEGIN:VTODO\r\nUID:$marker-1\r\nDTSTAMP:20260101T000000Z\r\n" +
                "SUMMARY:Live test task\r\nSTATUS:IN-PROCESS\r\nPERCENT-COMPLETE:40\r\nX-BOOKAH-LIVE:kept\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:r\r\nTRIGGER:-PT5M\r\nEND:VALARM\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
            client.put(href, ics, ifMatch = null)
            val created = client.get(href)
            assertNotNull(created)
            store.upsertObjects(cal.id, listOf(created!!))
            log("create test task", "read back")

            // 3. tick
            val writer = TaskWriter(store, client)
            val done = writer.complete(href)
            log("tick", done::class.simpleName.orEmpty())
            assertTrue(done is TaskWriteResult.Saved)
            val afterTick = client.get(href)!!
            assertEquals(afterTick.etag, (done as TaskWriteResult.Saved).etag)
            assertEquals(afterTick.etag, store.objectByHref(href)!!.etag)                // the server's own ETag
            assertTrue("STATUS:COMPLETED" in afterTick.ics && "PERCENT-COMPLETE:100" in afterTick.ics && Regex("COMPLETED:\\d{8}T\\d{6}Z").containsMatchIn(afterTick.ics))
            assertTrue("X-BOOKAH-LIVE:kept" in afterTick.ics && "TRIGGER:-PT5M" in afterTick.ics)
            log("tick read back", "completed, alarm and X- line kept")

            // 4. a write with a wrong ETag must get 412, never an overwrite
            val stale = try { client.put(href, afterTick.ics, ifMatch = "definitely-not-the-etag"); "accepted" } catch (e: PreconditionFailedException) { "412" }
            log("write with a wrong ETag", stale)
            assertEquals("412", stale)

            // 5. undo
            val back = writer.reopen(href, done.etag, done.before)
            log("undo", back::class.simpleName.orEmpty())
            assertTrue(back is TaskWriteResult.Saved)
            val afterUndo = client.get(href)!!
            // a true undo: in progress at 40 percent again, as before the tick
            assertTrue("STATUS:IN-PROCESS" in afterUndo.ics && "PERCENT-COMPLETE:40" in afterUndo.ics)
            assertFalse(Regex("^COMPLETED:", RegexOption.MULTILINE).containsMatchIn(afterUndo.ics))

            // 6. a change elsewhere, then a tick here: Conflict, nothing written, the phone's copy takes their version
            val theirs = afterUndo.ics.replace("SUMMARY:Live test task", "SUMMARY:Live test task changed elsewhere")
            client.put(href, theirs, ifMatch = afterUndo.etag)
            val conflict = writer.complete(href)
            log("tick after a change elsewhere", conflict::class.simpleName.orEmpty())
            assertTrue(conflict is TaskWriteResult.Conflict)
            val onServer = client.get(href)!!
            assertTrue("changed elsewhere" in onServer.ics && "STATUS:IN-PROCESS" in onServer.ics)
            assertEquals(onServer.etag, store.objectByHref(href)!!.etag)

            // 7. an undo that names an old ETag after a change elsewhere writes nothing
            val tick2 = writer.complete(href) as TaskWriteResult.Saved
            val moved = client.get(href)!!
            client.put(href, moved.ics.replace("changed elsewhere", "changed elsewhere again"), ifMatch = moved.etag)
            val lateUndo = writer.reopen(href, tick2.etag, tick2.before)
            log("undo after a change elsewhere", lateUndo::class.simpleName.orEmpty())
            assertTrue(lateUndo is TaskWriteResult.Conflict)
            assertTrue("STATUS:COMPLETED" in client.get(href)!!.ics)
        } finally {
            // the whole test list goes, for good (not to the trash), with the task inside
            val h = listHref ?: "$home$marker/"
            val (deleteStatus, _) = raw("DELETE", h, headers = mapOf("X-NC-CalDAV-No-Trashbin" to "1"))
            log("delete test task list for good", "HTTP $deleteStatus")
            val (_, homeListing) = raw("PROPFIND", home, """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>""", depth = "1")
            val left = Regex(marker).findAll(homeListing).count()
            log("entries of this test left in the calendar home", left.toString())
            assertTrue("permanent delete must succeed or the list was never made", deleteStatus == 204 || deleteStatus == 404)
            assertEquals("nothing of the test may be left (not even in the trash)", 0, left)
        }
    }
}
