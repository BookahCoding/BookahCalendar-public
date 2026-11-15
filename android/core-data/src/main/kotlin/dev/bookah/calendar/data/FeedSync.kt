package dev.bookah.calendar.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

enum class FeedStatus { Updated, NotModified, Offline, Rejected, Invalid }

/** What one feed did in a sync run. Never carries the address. */
data class FeedResult(val feedId: String, val status: FeedStatus, val events: Int)

fun feedHref(feedId: String) = "google:$feedId"

/**
 * Keeps the read-only feeds (Google's secret iCal address) up to date in the same store as the Nextcloud calendars.
 * A feed is fetched with If-None-Match, split into one object per UID and replaces the old copy. A failed fetch keeps the old copy.
 */
class FeedSync(private val store: CalendarStore, private val http: OkHttpClient) {

    suspend fun sync(feeds: List<FeedConfig>): List<FeedResult> {
        val calendars = store.syncFeedCalendars(feeds.map { FeedCalendar(feedHref(it.id), it.name, it.colorHex) }).associateBy { it.href }
        return feeds.map { feed ->
            val cal = calendars.getValue(feedHref(feed.id))
            try {
                one(feed, cal)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                FeedResult(feed.id, FeedStatus.Offline, store.objectCount(cal.id))
            } catch (e: IllegalArgumentException) {      // an address OkHttp cannot parse
                FeedResult(feed.id, FeedStatus.Invalid, store.objectCount(cal.id))
            }
        }
    }

    private suspend fun one(feed: FeedConfig, cal: CalendarInfo): FeedResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(feed.url).header("User-Agent", Http.USER_AGENT).apply {
            // only ask "has it changed?" when something is actually stored
            if (cal.syncToken != null && store.objectCount(cal.id) > 0) header("If-None-Match", cal.syncToken)
        }.build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 304 -> FeedResult(feed.id, FeedStatus.NotModified, store.objectCount(cal.id))
                !resp.isSuccessful -> FeedResult(feed.id, FeedStatus.Rejected, store.objectCount(cal.id))
                else -> {
                    val text = resp.body.string()
                    if (!text.contains("BEGIN:VCALENDAR")) return@use FeedResult(feed.id, FeedStatus.Invalid, store.objectCount(cal.id))
                    val objects = Expander.splitFeed(text).map { (uid, ics) ->
                        RemoteObject("${cal.href}/${java.net.URLEncoder.encode(uid, "UTF-8")}", ics.hashCode().toUInt().toString(16), ics)
                    }
                    store.clearObjects(cal.id)
                    store.upsertObjects(cal.id, objects)
                    store.setSyncToken(cal.id, resp.header("ETag"))
                    FeedResult(feed.id, FeedStatus.Updated, objects.size)
                }
            }
        }
    }
}
