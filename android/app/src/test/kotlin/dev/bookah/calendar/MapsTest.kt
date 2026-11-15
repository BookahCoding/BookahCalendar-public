package dev.bookah.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class MapsTest {
    @Test fun anAddressBecomesAGoogleMapsSearch() =
        assertEquals("https://www.google.com/maps/search/?api=1&query=Ermou%2010%2C%20Athens", locationUrl("Ermou 10, Athens"))

    @Test fun greekTextIsEncoded() =
        assertEquals("https://www.google.com/maps/search/?api=1&query=%CE%91%CE%B8%CE%AE%CE%BD%CE%B1", locationUrl("Αθήνα"))

    @Test fun symbolsThatWouldBreakTheLinkAreEncoded() =
        assertEquals("https://www.google.com/maps/search/?api=1&query=Caf%C3%A9%20%26%20Bar%20%232", locationUrl(" Café & Bar #2 "))

    @Test fun aWebLinkIsOpenedAsItIs() {
        assertEquals("https://meet.example.org/room-1", locationUrl("https://meet.example.org/room-1"))
        assertEquals("http://example.org/x", locationUrl("  http://example.org/x "))
    }
}
