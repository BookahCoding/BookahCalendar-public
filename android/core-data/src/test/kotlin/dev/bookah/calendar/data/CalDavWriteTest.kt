package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The real HTTP client against a local fake server: only the request shapes and the answers' meaning are checked here. */
class CalDavWriteTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CalDavClient

    @Before fun start() {
        server = MockWebServer(); server.start()
        client = CalDavClient(Http.newClient(), Session(server.url("/").toString().trimEnd('/'), "u", "p"))
    }

    @After fun stop() { runCatching { server.shutdown() } }

    @Test fun aNewObjectIsSentWithIfNoneMatchStarAndTheServersEtagIsReturned() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).addHeader("ETag", "\"abc123\""))
        val result = client.put("/remote.php/dav/calendars/u/c/x.ics", "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n", ifMatch = null)
        assertEquals("abc123", result.etag)
        val req = server.takeRequest()
        assertEquals("PUT", req.method); assertEquals("/remote.php/dav/calendars/u/c/x.ics", req.path)
        assertEquals("*", req.getHeader("If-None-Match")); assertNull(req.getHeader("If-Match"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("text/calendar"))
        assertTrue(req.body.readUtf8().startsWith("BEGIN:VCALENDAR"))
    }

    @Test fun anEditIsSentWithIfMatchAndTheQuotedEtag() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.put("/x.ics", "ics", ifMatch = "abc123")
        assertNull(result.etag)                                         // no ETag sent: the caller must read the object back
        val req = server.takeRequest()
        assertEquals("\"abc123\"", req.getHeader("If-Match")); assertNull(req.getHeader("If-None-Match"))
    }

    @Test fun deleteSendsIfMatch() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client.delete("/x.ics", "abc123")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method); assertEquals("\"abc123\"", req.getHeader("If-Match"))
    }

    @Test fun a412IsAPreconditionFailureNotAnOverwrite() = runTest {
        server.enqueue(MockResponse().setResponseCode(412))
        try { client.put("/x.ics", "ics", ifMatch = "old"); fail() } catch (e: PreconditionFailedException) { }
    }

    @Test fun errorsAreReportedInPlainWordsWithoutTheirBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("secret details"))
        try { client.delete("/x.ics", "e"); fail() } catch (e: CalDavException) { assertEquals(403, e.code); assertTrue("secret" !in e.message!!) }
        server.enqueue(MockResponse().setResponseCode(401))
        try { client.put("/x.ics", "ics", null); fail() } catch (e: CalDavException) { assertEquals(401, e.code) }
    }
}
