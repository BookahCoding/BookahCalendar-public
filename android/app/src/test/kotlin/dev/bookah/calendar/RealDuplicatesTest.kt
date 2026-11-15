package dev.bookah.calendar

import dev.bookah.calendar.data.CalendarInfo
import dev.bookah.calendar.data.Expander
import dev.bookah.calendar.data.Source
import dev.bookah.calendar.data.StoredObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Runs the duplicate rule over the owner's real Google feed and his real Nextcloud calendar (Phase 0 files, outside the repository).
 * On 15 Nov all 345 Google UIDs already existed in Personal, so every one of them must be left out. Skipped when the files are absent;
 * prints counts only, nothing from the files reaches the output.
 */
class RealDuplicatesTest {
    private val dir = File(System.getenv("BOOKAH_PRIVATE_DIR") ?: (System.getProperty("user.home") + "/BookahPrivate"))

    private fun calendarData(xml: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml)
        val n = doc.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
        return (0 until n.length).map { n.item(it).textContent }
    }

    /** An independent reading of the UIDs (lines unfolded first, trimmed), to compare with what the app's rule finds. */
    private fun uidsUnfolded(ics: String): Set<String> =
        Regex("(?m)^UID:(.*)$").findAll(ics.replace(Regex("\r?\n[ \t]"), "")).map { it.groupValues[1].trim() }.toSet()

    @Test fun everyRealGoogleEventThatNextcloudAlreadyHasIsLeftOut() {
        val raw = File(dir, "personal-raw.xml")
        val google = File(dir, "google.ics")
        assumeTrue("private Phase 0 files not present: skipped", raw.exists() && google.exists())

        val nextcloud = calendarData(raw).filter { "BEGIN:VEVENT" in it }.mapIndexed { i, ics -> StoredObject("n$i", 1, "e", ics) }
        val feed = Expander.splitFeed(google.readText()).mapIndexed { i, (_, ics) -> StoredObject("g$i", 2, "e", ics) }
        val calendars = listOf(
            CalendarInfo(1, "/cal/personal/", "Personal", "#00679E", Source.Nextcloud, true, true, null),
            CalendarInfo(2, "google:f", "Feed", "#7A5CA8", Source.Google, false, true, null),
        )

        val ncUids = nextcloud.flatMap { uidsUnfolded(it.ics) }.toSet()
        val expected = feed.count { o -> uidsUnfolded(o.ics).any { it in ncUids } }
        val (kept, hidden) = dropGoogleDuplicates(nextcloud + feed, calendars)

        println("feed objects=${feed.size}, nextcloud objects=${nextcloud.size}, hidden by the rule=$hidden, expected by an independent reading=$expected")
        assertEquals("the rule must find every duplicate an independent reading finds", expected, hidden)
        assertEquals("all Google objects were copies on 15 Nov", feed.size, hidden)
        assertEquals(nextcloud.size, kept.size)
    }
}
