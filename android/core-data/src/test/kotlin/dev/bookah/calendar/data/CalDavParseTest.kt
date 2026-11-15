package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalDavParseTest {
    private val calendars = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/" xmlns:cs="http://calendarserver.org/ns/">
 <d:response><d:href>/remote.php/dav/calendars/Demo/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/Demo/personal/</d:href>
  <d:propstat><d:prop><d:displayname>Personal</d:displayname>
   <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><a:calendar-color>#00679eff</a:calendar-color>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:write/></d:privilege><d:privilege><d:read/></d:privilege></d:current-user-privilege-set>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/Demo/contact_birthdays/</d:href>
  <d:propstat><d:prop><d:displayname>Contact birthdays</d:displayname>
   <d:resourcetype><d:collection/><c:calendar/></d:resourcetype><a:calendar-color>#E9D859</a:calendar-color>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set>
  </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/Demo/inbox/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/><c:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/Demo/tasks/</d:href>
  <d:propstat><d:prop><d:displayname>Tasks</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
   <c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/calendars/Demo/shared/</d:href>
  <d:propstat><d:prop><d:displayname>Shared, read only</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
   <c:supported-calendar-component-set><c:comp name="VEVENT"/></c:supported-calendar-component-set>
   <d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    @Test fun listsOnlyEventCalendars() {
        val list = CalDavClient.parseCalendarList(calendars)
        assertEquals(listOf("Personal", "Contact birthdays", "Shared, read only"), list.map { it.name })
    }

    @Test fun keepsServerPathsAndDropsAlphaFromColours() {
        val p = CalDavClient.parseCalendarList(calendars).first()
        assertEquals("/remote.php/dav/calendars/Demo/personal/", p.href)
        assertEquals("#00679E", p.colorHex)                       // 8-digit colour: alpha dropped, colour unchanged
    }

    @Test fun readsWriteRights() {
        val byName = CalDavClient.parseCalendarList(calendars).associateBy { it.name }
        assertTrue(byName.getValue("Personal").canWrite)
        assertFalse(byName.getValue("Shared, read only").canWrite)
    }

    @Test fun normalizesColours() {
        assertEquals("#E9D859", CalDavClient.normalizeColor("#e9d859"))
        assertEquals("#E9D859", CalDavClient.normalizeColor("#E9D859FF"))
        assertNull(CalDavClient.normalizeColor("red"))
        assertNull(CalDavClient.normalizeColor(null))
    }

    @Test fun parsesSyncDelta() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">
 <d:response><d:href>/cal/a.ics</d:href><d:propstat><d:prop><d:getetag>"e1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/cal/gone.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response>
 <d:sync-token>http://sabre.io/ns/sync/7</d:sync-token></d:multistatus>"""
        val d = CalDavClient.parseSyncDelta(xml)
        assertEquals("http://sabre.io/ns/sync/7", d.newToken)
        assertEquals(listOf(ChangedEntry("/cal/a.ics", "e1")), d.changed)
        assertEquals(listOf("/cal/gone.ics"), d.removed)
    }

    @Test fun parsesMultigetWithEscapedText() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
 <d:response><d:href>/cal/a.ics</d:href><d:propstat><d:prop><d:getetag>"abc"</d:getetag>
 <c:calendar-data>BEGIN:VCALENDAR
SUMMARY:Fish &amp; chips
END:VCALENDAR</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val o = CalDavClient.parseMultiget(xml).single()
        assertEquals("abc", o.etag)
        assertTrue("Fish & chips" in o.ics)
    }
}
