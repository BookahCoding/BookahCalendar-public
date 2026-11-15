package dev.bookah.calendar

import java.net.URLEncoder

/**
 * The address that a tapped event location opens. A location that already is a web link (a meeting room link, for example) is opened as it is;
 * anything else is searched in Google Maps, which opens the Google Maps app when it is installed and the browser otherwise.
 * Opening it is the owner's own tap: the app itself never contacts Google.
 */
fun locationUrl(location: String): String {
    val text = location.trim()
    if (text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)) return text
    val query = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    return "https://www.google.com/maps/search/?api=1&query=$query"
}
