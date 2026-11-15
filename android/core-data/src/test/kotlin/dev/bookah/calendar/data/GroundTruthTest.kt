package dev.bookah.calendar.data

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Runs the real expander over the owner's real calendar and compares it with what Nextcloud itself expanded (Phase 0 files).
 * The files live outside the repository (the folder named by the environment variable BOOKAH_PRIVATE_DIR); on any other checkout this test is skipped.
 * It prints counts only: no titles, no uids, nothing from the files reaches the output.
 */
class GroundTruthTest {
    private val dir = File(System.getenv("BOOKAH_PRIVATE_DIR") ?: (System.getProperty("user.home") + "/BookahPrivate"))

    private fun calendarData(xml: File): List<String> {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = dbf.newDocumentBuilder().parse(xml)
        val n = doc.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
        return (0 until n.length).map { n.item(it).textContent }
    }

    private fun unfold(ics: String) = ics.replace(Regex("\r?\n[ \t]"), "")

    private fun serverKeys(xml: File): Set<String> {
        val out = HashSet<String>()
        for (ics in calendarData(xml)) {
            for (b in Regex("BEGIN:VEVENT\r?\n(.*?)\r?\nEND:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(unfold(ics))) {
                val body = b.groupValues[1]
                val uid = Regex("(?m)^UID:(.*)$").find(body)!!.groupValues[1].trim()
                val line = Regex("(?m)^DTSTART([^:]*):(.*)$").find(body)!!
                val params = line.groupValues[1]
                val v = line.groupValues[2].trim()
                val allDay = v.length == 8
                val ms = if (allDay) {
                    LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                } else {
                    val ldt = LocalDateTime.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt(), v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), v.substring(13, 15).toInt())
                    val tz = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
                    ldt.atZone(if (v.endsWith("Z")) ZoneOffset.UTC else if (tz != null) ZoneId.of(tz) else ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                out.add("$uid|$ms|${if (allDay) "D" else "T"}")
            }
        }
        return out
    }

    @Test fun matchesNextcloudsOwnExpansionOfTheRealCalendar() {
        val raw = File(dir, "personal-raw.xml")
        val expandDir = File(dir, "expand")
        assumeTrue("private Phase 0 files not present: skipped", raw.exists() && expandDir.isDirectory)
        val sources = calendarData(raw).filter { "BEGIN:VEVENT" in it }.mapIndexed { i, ics -> ObjectSource(1, "o$i", ics, "gt$i", false) }
        val from = LocalDate.of(2025, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val to = LocalDate.of(2028, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val mine = Expander().expand(sources, from, to)
        var matched = 0; var missing = 0; var extra = 0; var total = 0
        for (y in 2025..2027) for (m in 1..12) {
            val file = File(expandDir, "$y-${"%02d".format(m)}.xml")
            if (!file.exists()) continue
            val ms0 = YearMonth.of(y, m).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val me0 = YearMonth.of(y, m).plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val truth = serverKeys(file)
            val inMonth = mine.filter { it.start < me0 && (it.end > ms0 || (it.end == it.start && it.start >= ms0)) }
                .map { "${it.uid}|${it.start}|${if (it.allDay) "D" else "T"}" }.toSet()
            total += truth.size
            matched += truth.intersect(inMonth).size
            missing += (truth - inMonth).size
            extra += (inMonth - truth).size
        }
        println("GROUND TRUTH: objects=${sources.size} server instances=$total matched=$matched missing=$missing extra=$extra")
        assertEquals("instances Nextcloud has and we lack", 0, missing)
        assertEquals("instances we have and Nextcloud lacks", 0, extra)
    }
}
