package dev.bookah.calendar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** What the sync engine needs from a calendar server; tests use a fake. */
interface CalDavApi {
    suspend fun listCalendars(): List<RemoteCalendar>
    suspend fun syncCollection(calendarHref: String, token: String?): SyncDelta
    suspend fun multiget(calendarHref: String, hrefs: List<String>): List<RemoteObject>

    /**
     * Saves one object. [ifMatch] = the ETag we read (an edit: refused with [PreconditionFailedException] if it changed);
     * null = a new object (refused if the address is already taken).
     */
    suspend fun put(objectHref: String, ics: String, ifMatch: String?): PutResult = throw UnsupportedOperationException("read-only API")

    /** Deletes one object only if it is still the version with [ifMatch]. */
    suspend fun delete(objectHref: String, ifMatch: String): Unit = throw UnsupportedOperationException("read-only API")

    /** One object as it is on the server now, or null when it is gone. */
    suspend fun get(objectHref: String): RemoteObject? = throw UnsupportedOperationException("read-only API")
}

/**
 * The few CalDAV requests the read-only app needs: list calendars, ask what changed since a sync token, fetch objects.
 * Only PROPFIND and REPORT are used: nothing here writes to the server.
 */
class CalDavClient(private val http: OkHttpClient, private val session: Session) : CalDavApi {
    private val xmlType = "application/xml; charset=utf-8".toMediaType()
    private val base = session.server.toHttpUrl()

    override suspend fun listCalendars(): List<RemoteCalendar> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:prop>
            <d:displayname/><d:resourcetype/><a:calendar-color/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop></d:propfind>"""
        val home = "/remote.php/dav/calendars/${session.user}/"
        return parseCalendarList(send("PROPFIND", home, body, depth = "1"))
    }

    override suspend fun syncCollection(calendarHref: String, token: String?): SyncDelta {
        val body = """<?xml version="1.0"?><d:sync-collection xmlns:d="DAV:"><d:sync-token>${token.orEmpty()}</d:sync-token><d:sync-level>1</d:sync-level><d:prop><d:getetag/></d:prop></d:sync-collection>"""
        return parseSyncDelta(send("REPORT", calendarHref, body, depth = null, tokenRequest = token != null))
    }

    override suspend fun multiget(calendarHref: String, hrefs: List<String>): List<RemoteObject> {
        if (hrefs.isEmpty()) return emptyList()
        val list = hrefs.joinToString("") { "<d:href>${xmlEscape(it)}</d:href>" }
        val body = """<?xml version="1.0"?><c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/><c:calendar-data/></d:prop>$list</c:calendar-multiget>"""
        return parseMultiget(send("REPORT", calendarHref, body, depth = "1"))
    }

    override suspend fun put(objectHref: String, ics: String, ifMatch: String?): PutResult {
        val headers = if (ifMatch != null) mapOf("If-Match" to quoted(ifMatch)) else mapOf("If-None-Match" to "*")
        return PutResult(write("PUT", objectHref, ics.toRequestBody(icsType), headers)?.let { cleanEtag(it) })
    }

    override suspend fun delete(objectHref: String, ifMatch: String) {
        write("DELETE", objectHref, null, mapOf("If-Match" to quoted(ifMatch)))
    }

    override suspend fun get(objectHref: String): RemoteObject? =
        multiget(objectHref.substringBeforeLast('/') + "/", listOf(objectHref)).firstOrNull()

    private val icsType = "text/calendar; charset=utf-8".toMediaType()

    private fun quoted(etag: String) = "\"" + etag.removeSurrounding("\"") + "\""

    /** A write request. Returns the new ETag header (or null). 412 -> [PreconditionFailedException]; anything else that is not a success -> [CalDavException]. */
    private suspend fun write(method: String, path: String, body: okhttp3.RequestBody?, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
        val url = base.resolve(path) ?: throw CalDavException(0, "Bad address")
        val req = Request.Builder().url(url).method(method, body)
            .header("Authorization", session.authHeader)
            .header("User-Agent", Http.USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(req).execute().use { resp ->
            resp.body.string()
            when (resp.code) {
                200, 201, 204 -> resp.header("ETag")
                412 -> throw PreconditionFailedException()
                401 -> throw CalDavException(401, "The server did not accept the user name or app password.")
                403 -> throw CalDavException(403, "The server does not allow changes to this calendar.")
                404 -> throw CalDavException(404, "The event or calendar was not found on the server.")
                else -> throw CalDavException(resp.code, "The server answered ${resp.code}.")
            }
        }
    }

    private suspend fun send(method: String, path: String, body: String, depth: String?, tokenRequest: Boolean = false): String = withContext(Dispatchers.IO) {
        val url = base.resolve(path) ?: throw CalDavException(0, "Bad address: $path")
        val req = Request.Builder().url(url)
            .method(method, body.toRequestBody(xmlType))
            .header("Authorization", session.authHeader)
            .header("User-Agent", Http.USER_AGENT)
            .apply { if (depth != null) header("Depth", depth) }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            when {
                resp.code == 207 -> text
                tokenRequest && (resp.code == 403 || resp.code == 409) -> throw SyncTokenInvalidException()
                resp.code == 401 -> throw CalDavException(401, "The server did not accept the user name or app password.")
                resp.code == 404 -> throw CalDavException(404, "The calendar address was not found on the server.")
                else -> throw CalDavException(resp.code, "The server answered ${resp.code}.")
            }
        }
    }

    companion object {
        private const val DAV = "DAV:"
        private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        private const val APPLE = "http://apple.com/ns/ical/"

        fun parseCalendarList(xml: String): List<RemoteCalendar> {
            val out = ArrayList<RemoteCalendar>()
            for (resp in responses(xml)) {
                val prop = okProp(resp) ?: continue
                val resourceType = child(prop, DAV, "resourcetype") ?: continue
                if (child(resourceType, CALDAV, "calendar") == null) continue       // skips subscriptions, inbox, outbox, trash
                // no component list means the server takes events (as before); a list decides events, tasks or both; neither (journals) is skipped
                val comps = child(prop, CALDAV, "supported-calendar-component-set")?.let { c -> children(c, CALDAV, "comp").map { it.getAttribute("name").uppercase() } }
                val hasEvents = comps == null || "VEVENT" in comps
                val hasTasks = comps != null && "VTODO" in comps
                val kind = when {
                    hasEvents && hasTasks -> ListKind.Both
                    hasEvents -> ListKind.Events
                    hasTasks -> ListKind.Tasks
                    else -> continue
                }
                val href = text(resp, DAV, "href") ?: continue
                val privs = child(prop, DAV, "current-user-privilege-set")
                    ?.let { p -> descendants(p).map { it.localName } }.orEmpty().toSet()
                val canWrite = "all" in privs || "write" in privs || "write-content" in privs || "bind" in privs
                out.add(RemoteCalendar(href, text(prop, DAV, "displayname")?.ifBlank { null } ?: href.trimEnd('/').substringAfterLast('/'), normalizeColor(text(prop, APPLE, "calendar-color")), canWrite, kind))
            }
            return out
        }

        fun parseSyncDelta(xml: String): SyncDelta {
            val doc = parse(xml)
            val token = doc.documentElement.let { text(it, DAV, "sync-token") }
            val changed = ArrayList<ChangedEntry>()
            val removed = ArrayList<String>()
            for (resp in responses(doc)) {
                val href = text(resp, DAV, "href") ?: continue
                if (href.endsWith("/")) continue                               // the collection itself
                val status = text(resp, DAV, "status")
                if (status != null && " 404 " in status) { removed.add(href); continue }
                val prop = okProp(resp) ?: continue
                changed.add(ChangedEntry(href, cleanEtag(text(prop, DAV, "getetag").orEmpty())))
            }
            return SyncDelta(token, changed, removed)
        }

        fun parseMultiget(xml: String): List<RemoteObject> {
            val out = ArrayList<RemoteObject>()
            for (resp in responses(xml)) {
                val href = text(resp, DAV, "href") ?: continue
                val prop = okProp(resp) ?: continue
                val data = text(prop, CALDAV, "calendar-data") ?: continue
                out.add(RemoteObject(href, cleanEtag(text(prop, DAV, "getetag").orEmpty()), data))
            }
            return out
        }

        /** "#00679eff" and "#00679E" both become "#00679E": the alpha part is dropped, the colour itself is never altered. */
        fun normalizeColor(raw: String?): String? {
            val s = raw?.trim() ?: return null
            val m = Regex("^#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$").matchEntire(s) ?: return null
            return "#" + m.groupValues[1].uppercase()
        }

        private fun cleanEtag(s: String) = s.trim().removeSurrounding("\"").removePrefix("W/").removeSurrounding("\"")

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // the server is trusted, but a document type declaration is never needed here
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        private fun responses(xml: String) = responses(parse(xml))
        private fun responses(doc: org.w3c.dom.Document): List<Element> =
            doc.getElementsByTagNameNS(DAV, "response").let { n -> (0 until n.length).map { n.item(it) as Element } }

        /** The prop block of the propstat whose status is 200, or null. */
        private fun okProp(resp: Element): Element? {
            for (ps in children(resp, DAV, "propstat")) {
                val status = text(ps, DAV, "status").orEmpty()
                if (" 200 " in status || status.isEmpty()) return child(ps, DAV, "prop")
            }
            return null
        }

        private fun children(e: Element, ns: String, name: String): List<Element> {
            val out = ArrayList<Element>()
            var n: Node? = e.firstChild
            while (n != null) { if (n is Element && n.namespaceURI == ns && n.localName == name) out.add(n); n = n.nextSibling }
            return out
        }

        private fun child(e: Element, ns: String, name: String) = children(e, ns, name).firstOrNull()
        private fun text(e: Element, ns: String, name: String) = child(e, ns, name)?.textContent
        private fun descendants(e: Element): List<Element> {
            val out = ArrayList<Element>()
            val all = e.getElementsByTagName("*")
            for (i in 0 until all.length) out.add(all.item(i) as Element)
            return out
        }
    }
}
