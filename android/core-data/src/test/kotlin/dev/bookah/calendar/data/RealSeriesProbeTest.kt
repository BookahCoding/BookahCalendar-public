package dev.bookah.calendar.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/** Counts only: what the real repeating events look like (forms of DTSTART, rules, overrides, people). Skipped without the private files. */
class RealSeriesProbeTest {
    private val dir = File(System.getenv("BOOKAH_PRIVATE_DIR") ?: (System.getProperty("user.home") + "/BookahPrivate"))

    @Test fun describeTheRealRepeatingObjects() {
        val raw = File(dir, "personal-raw.xml")
        assumeTrue("private Phase 0 files not present: skipped", raw.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(raw)
        val n = doc.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
        val objects = (0 until n.length).map { n.item(it).textContent }.filter { "BEGIN:VEVENT" in it }
        // the app's own rule (the text RRULE also appears inside time-zone definitions, which must not count)
        val series = objects.filter { IcsEdit.editability(it) == Editability.ReadOnly(ReadOnlyReason.Repeating) }

        var rdate = 0; var exdate = 0; var overrides = 0; var people = 0; var count = 0; var until = 0; var severalRrule = 0; var noMaster = 0
        val kinds = HashMap<String, Int>(); val freq = HashMap<String, Int>(); val zoneOk = HashMap<String, Int>()
        for (o in series) {
            val lines = IcsEdit.logicalLines(o)
            val events = ArrayList<MutableList<String>>(); var cur: MutableList<String>? = null
            for (l in lines) {
                if (IcsEdit.name(l) == "BEGIN" && IcsEdit.value(l).trim().uppercase() == "VEVENT") cur = ArrayList()
                cur?.add(l)
                if (IcsEdit.name(l) == "END" && IcsEdit.value(l).trim().uppercase() == "VEVENT") { events.add(cur!!); cur = null }
            }
            val masters = events.filter { e -> e.none { IcsEdit.name(it) == "RECURRENCE-ID" } }
            if (masters.size != 1) noMaster++
            overrides += events.count { e -> e.any { IcsEdit.name(it) == "RECURRENCE-ID" } }
            val master = masters.firstOrNull() ?: continue
            val rr = master.filter { IcsEdit.name(it) == "RRULE" }
            if (rr.size > 1) severalRrule++
            rr.firstOrNull()?.let { r ->
                val v = IcsEdit.value(r)
                freq.merge(Regex("FREQ=([A-Z]+)").find(v)?.groupValues?.get(1) ?: "?", 1, Int::plus)
                if ("COUNT=" in v) count++
                if ("UNTIL=" in v) until++
            }
            if (master.any { IcsEdit.name(it) == "RDATE" }) rdate++
            if (master.any { IcsEdit.name(it) == "EXDATE" }) exdate++
            if (events.any { e -> e.any { IcsEdit.name(it) == "ORGANIZER" || IcsEdit.name(it) == "ATTENDEE" } }) people++
            val ds = master.firstOrNull { IcsEdit.name(it) == "DTSTART" } ?: continue
            val dsu = IcsEdit.unfold(ds)
            val kind = when {
                "VALUE=DATE" in dsu.uppercase() && "TZID" !in dsu.uppercase() -> "date"
                "TZID=" in dsu.uppercase() -> "zoned"
                IcsEdit.value(ds).trim().endsWith("Z") -> "utc"
                else -> "floating"
            }
            kinds.merge(kind, 1, Int::plus)
            if (kind == "zoned") {
                val tz = Regex("TZID=([^;:]+)", RegexOption.IGNORE_CASE).find(dsu)?.groupValues?.get(1)?.trim('"')
                val ok = runCatching { ZoneId.of(tz!!) }.isSuccess
                zoneOk.merge(if (ok) "resolvable" else "NOT resolvable", 1, Int::plus)
            }
        }
        println("all objects=${objects.size}, series objects=${series.size}; kinds=$kinds; zones=$zoneOk; freq=$freq; with RDATE=$rdate, EXDATE=$exdate, COUNT=$count, UNTIL=$until, several RRULE=$severalRrule, without exactly one master=$noMaster, edited occurrences=$overrides, with people=$people")
    }
}
