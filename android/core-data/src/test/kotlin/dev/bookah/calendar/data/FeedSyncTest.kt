package dev.bookah.calendar.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FeedSyncTest {
    private lateinit var server: MockWebServer
    private val store = MemoryCalendarStore()
    private val http = Http.newClient()

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { runCatching { server.shutdown() } }

    private fun r(body: String, code: Int = 200, etag: String? = null) =
        MockResponse().setResponseCode(code).setBody(body).also { if (etag != null) it.addHeader("ETag", etag) }

    private fun feed(path: String = "/feed.ics") = FeedConfig("f1", "Generated feed", server.url(path).toString(), "#7A5CA8")

    private fun ics(vararg uids: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + uids.joinToString("") {
        "BEGIN:VEVENT\r\nUID:$it\r\nDTSTAMP:20260101T000000Z\r\nDTSTART:20261120T100000Z\r\nDTEND:20261120T110000Z\r\nSUMMARY:Generated $it\r\nEND:VEVENT\r\n"
    } + "END:VCALENDAR\r\n"

    @Test fun firstFetchStoresOneObjectPerUidAndKeepsTheEtag() = runTest {
        server.enqueue(r(ics("a@x", "b@x", "c@x"), etag = "\"v1\""))
        val r = FeedSync(store, http).sync(listOf(feed()))
        assertEquals(FeedStatus.Updated, r.single().status)
        val cal = store.calendars().single()
        assertEquals(Source.Google, cal.source)
        assertEquals(false, cal.editable)
        assertEquals(3, store.objectCount(cal.id))
        assertEquals("\"v1\"", cal.syncToken)
    }

    @Test fun secondFetchSendsIfNoneMatchAndAcceptsNotModified() = runTest {
        server.enqueue(r(ics("a@x"), etag = "\"v1\""))
        server.enqueue(r("", 304))
        val sync = FeedSync(store, http)
        sync.sync(listOf(feed()))
        val r = sync.sync(listOf(feed()))
        assertEquals(FeedStatus.NotModified, r.single().status)
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        assertEquals(1, store.objectCount(store.calendars().single().id))
    }

    @Test fun aRefusedOrBrokenFetchKeepsTheOldCopy() = runTest {
        server.enqueue(r(ics("a@x", "b@x")))
        server.enqueue(r("", 404))
        server.enqueue(r("<html>not a calendar</html>"))
        val sync = FeedSync(store, http)
        sync.sync(listOf(feed()))
        assertEquals(FeedStatus.Rejected, sync.sync(listOf(feed())).single().status)
        assertEquals(FeedStatus.Invalid, sync.sync(listOf(feed())).single().status)
        assertEquals(2, store.objectCount(store.calendars().single().id))
    }

    @Test fun offlineKeepsTheOldCopy() = runTest {
        server.enqueue(r(ics("a@x")))
        val f = feed()
        val sync = FeedSync(store, http)
        sync.sync(listOf(f))
        server.shutdown()
        assertEquals(FeedStatus.Offline, sync.sync(listOf(f)).single().status)
        assertEquals(1, store.objectCount(store.calendars().single().id))
    }

    @Test fun removingAFeedRemovesItsCalendarAndEventsButNotNextcloud() = runTest {
        store.replaceNextcloudCalendars(listOf(RemoteCalendar("/cal/a/", "Mine", "#00679E", true)))
        server.enqueue(r(ics("a@x")))
        val sync = FeedSync(store, http)
        sync.sync(listOf(feed()))
        assertEquals(2, store.calendars().size)
        sync.sync(emptyList())
        val left = store.calendars().single()
        assertEquals(Source.Nextcloud, left.source)
        assertNull(store.calendars().firstOrNull { it.source == Source.Google })
    }

    @Test fun showHideChoiceSurvivesASync() = runTest {
        server.enqueue(r(ics("a@x")))
        server.enqueue(r(ics("a@x")))
        val sync = FeedSync(store, http)
        sync.sync(listOf(feed()))
        store.setVisible(store.calendars().single().id, false)
        sync.sync(listOf(feed()))
        assertEquals(false, store.calendars().single().visible)
    }
}
