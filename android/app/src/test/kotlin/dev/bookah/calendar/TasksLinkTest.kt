package dev.bookah.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** The link that opens one task in BookahTasks. Generated values only. */
class TasksLinkTest {
    /** Reads a value back the way any percent-decoder does (the link never holds "+", so the "+ is a space" rule never matters). */
    private fun query(link: String?): Map<String, String> {
        assertTrue(link!!.startsWith("bookahtasks://task?"))
        return link.substringAfter('?').split('&').associate { p -> p.substringBefore('=') to URLDecoder.decode(p.substringAfter('='), "UTF-8") }
    }

    @Test fun aPlainTaskGivesTheExpectedLink() {
        assertEquals("bookahtasks://task?uid=abc-123%40invalid&list=%2Fremote.php%2Fdav%2Fcalendars%2Fu%2Ftasks%2F",
            bookahTasksLink("abc-123@invalid", "/remote.php/dav/calendars/u/tasks/"))
    }

    @Test fun specialCharactersAreEncodedAndComeBackExactly() {
        val uid = "a b+c&d=e#f%g/h?i@j*k~l.m_n-o:p;q,r'\"s"
        val list = "/cal/my list+&=#%/"
        val link = bookahTasksLink(uid, list)!!
        val q = query(link)
        assertEquals(uid, q["uid"])
        assertEquals(list, q["list"])
        assertEquals(2, q.size)                                        // an "&" or "=" in a value never makes a third part
        val values = link.substringAfter('?')
        assertFalse(" " in values || "+" in values || "#" in values || "*" in values)
        assertTrue("%20" in link)
    }

    @Test fun greekAndEmojiAreUtf8() {
        val uid = "Αγορά-ψωμιού-🍞@invalid"
        val link = bookahTasksLink(uid, "/cal/Εργασίες/")!!
        assertTrue(link.all { it.code < 128 })                         // only plain ASCII in the link itself
        assertTrue("%CE%91" in link)                                   // capital alpha in UTF-8
        assertEquals(uid, query(link)["uid"])
        assertEquals("/cal/Εργασίες/", query(link)["list"])
    }

    @Test fun aLongUidIsKeptWhole() {
        val uid = "generated-" + "x".repeat(282) + "@invalid"
        assertEquals(300, uid.length)
        assertEquals(uid, query(bookahTasksLink(uid, "/cal/tasks/"))["uid"])
        val limit = "g".repeat(1024)
        assertEquals(limit, query(bookahTasksLink(limit, "/cal/tasks/"))["uid"])
    }

    @Test fun aUidOver1024CharactersGivesNoLink() {
        assertNull(bookahTasksLink("g".repeat(1025), "/cal/tasks/"))
    }

    @Test fun aLinkOver4096CharactersGivesNoLink() {
        // 1000 Greek letters are 2000 bytes, 6000 characters once encoded
        assertNull(bookahTasksLink("Α".repeat(1000), "/cal/tasks/"))
        assertNull(bookahTasksLink("short@invalid", "/" + "l".repeat(4100)))
        assertTrue(bookahTasksLink("g".repeat(1024), "/" + "l".repeat(2900))!!.length <= 4096)
    }

    @Test fun withoutAListTheLinkHasOnlyTheUid() {
        assertEquals("bookahtasks://task?uid=abc%40invalid", bookahTasksLink("abc@invalid", null))
    }

    @Test fun anEmptyValueStaysEmpty() {
        assertEquals("bookahtasks://task?uid=&list=", bookahTasksLink("", ""))
    }

    @Test fun theCertificateFingerprintIsReadAsBytes() {
        assertEquals(64, BOOKAH_TASKS_CERT_SHA256.length)
        val bytes = fingerprintBytes(BOOKAH_TASKS_CERT_SHA256)
        assertEquals(32, bytes.size)
        assertEquals(0x63.toByte(), bytes[0])
        assertEquals(0x46.toByte(), bytes[31])
    }
}
