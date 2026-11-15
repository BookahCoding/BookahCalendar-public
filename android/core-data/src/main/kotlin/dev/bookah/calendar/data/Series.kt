package dev.bookah.calendar.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Which occurrences of a repeating event a change applies to ("This event / This and following events / All events"). */
enum class Scope { This, Following, All }

/**
 * One occurrence as the user sees it. [originalStartMs] is its slot in the series (what the server calls RECURRENCE-ID; it differs from
 * [startMs] when that occurrence was edited and moved); [startMs] and [endMs] are what is shown.
 */
data class Occurrence(val originalStartMs: Long, val startMs: Long, val endMs: Long)

/** What a change to a series does to the stored objects. */
sealed interface SeriesChange {
    /** The object's text becomes [ics]. */
    data class Replace(val ics: String) : SeriesChange

    /** The whole object is deleted. */
    data object DeleteObject : SeriesChange

    /** The object becomes [oldIcs] (the earlier part of the series) and a NEW object [newIcs] with UID [newUid] holds the later part. */
    data class Split(val oldIcs: String, val newIcs: String, val newUid: String) : SeriesChange
}

/**
 * Changes and deletes occurrences of a repeating event by text surgery (docs/phase-4-design.md section 2): only the lines that must change are
 * touched; everything else in the object stays as it was. An occurrence is named by its ORIGINAL start (UTC milliseconds), which is written in the
 * same form as the series' own DTSTART (UTC, a time zone with local time, or a date) so the server matches it exactly.
 */
object SeriesEdit {
    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val CRLF = "\r\n"

    // ---------------------------------------------------------------------------------------------- reading the object

    /** The VEVENTs of one calendar object, with everything before and after them (time zones, calendar properties) kept as it was. */
    internal class Doc(val head: List<String>, val events: MutableList<List<String>>, val tail: List<String>) {
        fun render(): String = (head + events.flatten() + tail).joinToString(CRLF) + CRLF
    }

    internal fun parseDoc(ics: String): Doc? {
        val lines = IcsEdit.logicalLines(ics)
        val first = lines.indexOfFirst { IcsEdit.name(it) == "BEGIN" && IcsEdit.value(it).trim().uppercase() == "VEVENT" }
        val last = lines.indexOfLast { IcsEdit.name(it) == "END" && IcsEdit.value(it).trim().uppercase() == "VEVENT" }
        if (first < 0 || last < first) return null
        val events = ArrayList<List<String>>()
        var i = first
        while (i <= last) {
            if (!(IcsEdit.name(lines[i]) == "BEGIN" && IcsEdit.value(lines[i]).trim().uppercase() == "VEVENT")) return null   // something else between events
            var depth = 0
            var j = i
            while (j <= last) {
                when (IcsEdit.name(lines[j])) { "BEGIN" -> depth++; "END" -> depth-- }
                if (depth == 0) break
                j++
            }
            if (depth != 0) return null
            events.add(lines.subList(i, j + 1).toList())
            i = j + 1
        }
        return Doc(lines.subList(0, first).toList(), events, lines.subList(last + 1, lines.size).toList())
    }

    /** An event as its begin line, its direct properties and nested blocks (reminders), and its end line. */
    internal class Ev(val begin: String, val items: MutableList<IcsEdit.Item>, val end: String) {
        fun lines(): List<String> = listOf(begin) + items.flatMap { it.lines } + end
        fun props(name: String): List<String> = items.filter { !it.isBlock && it.name == name }.map { it.lines[0] }
        fun prop(name: String): String? = props(name).firstOrNull()
        fun has(name: String) = items.any { !it.isBlock && it.name == name }
        fun remove(name: String) { items.removeAll { !it.isBlock && it.name == name } }
        fun insertAfter(afterName: String, newLine: String) {
            val at = items.indexOfLast { !it.isBlock && it.name == afterName }
            val item = IcsEdit.Item(IcsEdit.name(newLine), listOf(newLine), false)
            if (at >= 0) items.add(at + 1, item) else { val b = items.indexOfFirst { it.isBlock }; items.add(if (b >= 0) b else items.size, item) }
        }
        fun replace(name: String, newLine: String) {
            val at = items.indexOfFirst { !it.isBlock && it.name == name }
            items.removeAll { !it.isBlock && it.name == name }
            val item = IcsEdit.Item(name, listOf(newLine), false)
            if (at >= 0) items.add(minOf(at, items.size), item) else insertAfter("UID", newLine)
        }
        val isOverride get() = has("RECURRENCE-ID")
    }

    internal fun ev(lines: List<String>): Ev {
        val p = IcsEdit.parse("BEGIN:VCALENDAR\r\n" + lines.joinToString(CRLF) + CRLF + "END:VCALENDAR\r\n")
        return Ev(p.lines[p.begin], p.body.toMutableList(), p.lines[p.end])
    }

    // ---------------------------------------------------------------------------------------------- time forms

    /** How the series writes its times. Floating times (no zone, no Z) and unknown zones are not supported. */
    internal sealed interface Form {
        data object Utc : Form
        data class Zoned(val zone: ZoneId) : Form
        data object Date : Form
    }

    internal fun rawParams(line: String): String {
        val u = IcsEdit.unfold(line)
        val c = IcsEdit.colonAt(u)
        return (if (c < 0) u else u.substring(0, c)).substringAfter(';', "")
    }

    internal fun formOf(line: String?): Form? {
        line ?: return null
        val params = rawParams(line).uppercase()
        val value = IcsEdit.value(line).trim()
        return when {
            "TZID=" in params -> {
                val id = Regex("TZID=([^;:]+)", RegexOption.IGNORE_CASE).find(rawParams(line))?.groupValues?.get(1)?.trim('"') ?: return null
                runCatching { ZoneId.of(id) }.getOrNull()?.let { Form.Zoned(it) }
            }
            "VALUE=DATE" in params && "VALUE=DATE-TIME" !in params -> Form.Date
            value.endsWith("Z") -> Form.Utc
            else -> null
        }
    }

    private fun zoneOf(f: Form): ZoneId = when (f) { is Form.Zoned -> f.zone; else -> ZoneOffset.UTC }

    internal fun valueText(f: Form, ms: Long): String = when (f) {
        Form.Utc -> IcsEdit.utc(ms)
        is Form.Zoned -> LOCAL.format(Instant.ofEpochMilli(ms).atZone(f.zone))
        Form.Date -> DATE.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))
    }

    internal fun parseValue(f: Form, text: String): Long? = runCatching {
        val t = text.trim()
        when (f) {
            Form.Utc -> LocalDateTime.parse(t.removeSuffix("Z"), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
            is Form.Zoned -> LocalDateTime.parse(t, LOCAL).atZone(f.zone).toInstant().toEpochMilli()
            Form.Date -> LocalDate.parse(t, DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
    }.getOrNull()

    private fun startMsOf(line: String?): Long? { val f = formOf(line) ?: return null; return parseValue(f, IcsEdit.value(line!!)) }

    /** A property line for [name] in the same form (parameters included) as [like], for the instant [ms]. */
    private fun lineLike(name: String, like: String, ms: Long): String {
        val f = formOf(like)!!
        return IcsEdit.line(name, valueText(f, ms), rawParams(like))
    }

    // ---------------------------------------------------------------------------------------------- what can be changed

    private class Series(val doc: Doc, val master: Ev, val overrides: List<Ev>, val form: Form, val masterStartMs: Long)

    private fun series(ics: String): Series? {
        val doc = parseDoc(ics) ?: return null
        val evs = doc.events.map { ev(it) }
        val masters = evs.filter { !it.isOverride }
        val master = masters.singleOrNull() ?: return null
        if (master.props("RRULE").size != 1 || master.has("RDATE")) return null
        val uid = master.prop("UID")?.let { IcsEdit.value(it).trim() }
        val overrides = evs.filter { it.isOverride }
        if (overrides.any { o -> o.prop("UID")?.let { IcsEdit.value(it).trim() } != uid }) return null
        val dtstart = master.prop("DTSTART") ?: return null
        val form = formOf(dtstart) ?: return null
        val start = parseValue(form, IcsEdit.value(dtstart)) ?: return null
        if (overrides.any { o -> formOf(o.prop("DTSTART")) == null || formOf(o.prop("RECURRENCE-ID")) == null }) return null
        return Series(doc, master, overrides, form, start)
    }

    /** [Editability.EditableSeries] for a simple repeating event, otherwise why not (docs/phase-4-design.md 2.3). */
    fun editability(ics: String): Editability {
        val doc = parseDoc(ics) ?: return Editability.ReadOnly(ReadOnlyReason.Repeating)
        val evs = doc.events.map { ev(it) }
        if (evs.any { it.has("ORGANIZER") || it.has("ATTENDEE") }) return Editability.ReadOnly(ReadOnlyReason.Invitation)
        return if (series(ics) != null) Editability.EditableSeries else Editability.ReadOnly(ReadOnlyReason.Repeating)
    }

    // ---------------------------------------------------------------------------------------------- helpers on one event

    /** [lines] edited by the plain-event writer, in the context of the object's head (time zones). */
    private fun editedEvent(doc: Doc, lines: List<String>, edit: EventEdit, whenLines: List<String>?, nowMs: Long): List<String> {
        val synthetic = (doc.head + lines + doc.tail).joinToString(CRLF) + CRLF
        val out = IcsEdit.applyEditCore(synthetic, edit, nowMs, whenLines, requireEditable = false, forceTouch = true)
        return parseDoc(out)!!.events.single()
    }

    private fun touched(doc: Doc, lines: List<String>, nowMs: Long) = editedEvent(doc, lines, EventEdit(), null, nowMs)

    /** The series' own rule with COUNT and UNTIL removed. */
    private fun ruleParts(rrule: String): List<String> =
        IcsEdit.value(rrule).trim().split(';').filter { it.isNotBlank() && !it.uppercase().startsWith("COUNT=") && !it.uppercase().startsWith("UNTIL=") }

    private fun untilBefore(f: Form, instanceStartMs: Long): String = when (f) {
        Form.Date -> DATE.format(Instant.ofEpochMilli(instanceStartMs).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1))   // the day before
        else -> IcsEdit.utc(instanceStartMs - 1000)                                                                          // one second before, in UTC
    }

    /** EXDATE lines with only the values for which [keep] is true (a line with no value left is dropped). */
    private fun filterExdates(e: Ev, form: Form, keep: (Long) -> Boolean) {
        val lines = e.props("EXDATE")
        if (lines.isEmpty()) return
        val at = e.items.indexOfFirst { !it.isBlock && it.name == "EXDATE" }
        e.remove("EXDATE")
        var pos = at
        for (l in lines) {
            val f = formOf(l) ?: form
            val kept = IcsEdit.value(l).split(',').filter { v -> parseValue(f, v)?.let(keep) ?: true }
            if (kept.isNotEmpty()) { e.items.add(minOf(pos, e.items.size), IcsEdit.Item("EXDATE", listOf(IcsEdit.line("EXDATE", kept.joinToString(","), rawParams(l))), false)); pos++ }
        }
    }

    private fun overrideStartMs(o: Ev): Long? = o.prop("RECURRENCE-ID")?.let { l -> formOf(l)?.let { parseValue(it, IcsEdit.value(l)) } }

    // ---------------------------------------------------------------------------------------------- delete

    /** Deleting [scope] of the series from the occurrence that originally starts at [instanceStartMs]. Null when the object is not a simple series. */
    fun delete(ics: String, instanceStartMs: Long, scope: Scope, nowMs: Long): SeriesChange? {
        val s = series(ics) ?: return null
        if (scope == Scope.All) return SeriesChange.DeleteObject
        if (scope == Scope.Following && instanceStartMs <= s.masterStartMs) return SeriesChange.DeleteObject      // from the first occurrence on = all
        val master = ev(s.master.lines())
        val keptOverrides: List<Ev>
        when (scope) {
            Scope.This -> {
                val like = master.prop("DTSTART")!!
                val line = lineLike("EXDATE", like, instanceStartMs)
                val already = master.props("EXDATE").any { l -> IcsEdit.value(l).split(',').any { v -> parseValue(formOf(l) ?: s.form, v) == instanceStartMs } }
                if (!already) master.insertAfter(if (master.has("EXDATE")) "EXDATE" else "RRULE", line)
                keptOverrides = s.overrides.filter { overrideStartMs(it) != instanceStartMs }       // an edited copy of this occurrence goes too
            }
            else -> {
                val rr = master.prop("RRULE")!!
                val parts = ruleParts(rr) + ("UNTIL=" + untilBefore(s.form, instanceStartMs))      // COUNT and any old UNTIL are replaced by the new end
                master.replace("RRULE", IcsEdit.line("RRULE", parts.joinToString(";")))
                filterExdates(master, s.form) { it < instanceStartMs }
                keptOverrides = s.overrides.filter { (overrideStartMs(it) ?: 0L) < instanceStartMs }
            }
        }
        val newMaster = touched(s.doc, master.lines(), nowMs)
        val events = ArrayList<List<String>>()
        events.add(newMaster); keptOverrides.forEach { events.add(it.lines()) }
        return SeriesChange.Replace(Doc(s.doc.head, events, s.doc.tail).render())
    }

    // ---------------------------------------------------------------------------------------------- edit

    /** The ready-made DTSTART and DTEND lines that put [w] into the form (and parameters) of [startLike] and [endLike]. */
    private fun linesFor(w: EventWhen, startLike: String, endLike: String?): List<String> {
        val f = formOf(startLike)!!
        return when (w) {
            is EventWhen.Timed -> {
                require(f != Form.Date) { "a series of all-day events cannot be made timed here" }
                listOf(lineLike("DTSTART", startLike, w.startMs), lineLike("DTEND", endLike ?: startLike, w.endMs))
            }
            is EventWhen.AllDay -> {
                require(f == Form.Date) { "a series of timed events cannot be made all-day here" }
                val first = w.first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val endEx = w.lastInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                listOf(lineLike("DTSTART", startLike, first), lineLike("DTEND", endLike ?: startLike, endEx))
            }
        }
    }

    /** How long each occurrence of the series lasts (master end minus master start); a day for an all-day series without an end. */
    private fun masterDurationMs(s: Series): Long {
        val end = s.master.prop("DTEND")
        val endMs = end?.let { l -> formOf(l)?.let { parseValue(it, IcsEdit.value(l)) } }
        return if (endMs != null) maxOf(0L, endMs - s.masterStartMs) else if (s.form == Form.Date) 86_400_000L else 0L
    }

    /** The master's own start and end lines after the whole series moves by the same amount as this occurrence did (in local time). */
    private fun shiftedMasterLines(s: Series, w: EventWhen, instanceStartMs: Long): List<String> {
        val startLike = s.master.prop("DTSTART")!!
        val endLike = s.master.prop("DTEND")
        val zone = zoneOf(s.form)
        return when (w) {
            is EventWhen.Timed -> {
                require(s.form != Form.Date) { "a series of all-day events cannot be made timed here" }
                val oldLocal = Instant.ofEpochMilli(instanceStartMs).atZone(zone).toLocalDateTime()
                val newLocal = Instant.ofEpochMilli(w.startMs).atZone(zone).toLocalDateTime()
                val masterLocal = Instant.ofEpochMilli(s.masterStartMs).atZone(zone).toLocalDateTime()
                val newMasterStartMs = masterLocal.plus(Duration.between(oldLocal, newLocal)).atZone(zone).toInstant().toEpochMilli()
                listOf(lineLike("DTSTART", startLike, newMasterStartMs), lineLike("DTEND", endLike ?: startLike, newMasterStartMs + (w.endMs - w.startMs)))
            }
            is EventWhen.AllDay -> {
                require(s.form == Form.Date) { "a series of timed events cannot be made all-day here" }
                val masterDay = Instant.ofEpochMilli(s.masterStartMs).atZone(ZoneOffset.UTC).toLocalDate()
                val oldDay = Instant.ofEpochMilli(instanceStartMs).atZone(ZoneOffset.UTC).toLocalDate()
                val newFirst = masterDay.plusDays(java.time.temporal.ChronoUnit.DAYS.between(oldDay, w.first))
                val newLast = newFirst.plusDays(java.time.temporal.ChronoUnit.DAYS.between(w.first, w.lastInclusive))
                linesFor(EventWhen.AllDay(newFirst, newLast), startLike, endLike)
            }
        }
    }

    /**
     * Applying [edit] to [scope] of the series, for the occurrence [occ] (named by its original slot, see [Occurrence]).
     * [newUid] names the new object of a "this and following" split. Null when the object is not a simple series.
     * Throws IllegalArgumentException for a change that makes no sense here (a timed series made all-day, an end before the start).
     */
    fun edit(ics: String, occ: Occurrence, edit: EventEdit, scope: Scope, nowMs: Long, newUid: String): SeriesChange? {
        val s = series(ics) ?: return null
        edit.whenAt?.let { require(IcsEdit.validate(it) == null) { "end before start" } }
        val noWhen = edit.copy(whenAt = null)
        return when {
            scope == Scope.All || (scope == Scope.Following && occ.originalStartMs <= s.masterStartMs) -> {
                val lines = editedEvent(s.doc, s.master.lines(), noWhen, edit.whenAt?.let { shiftedMasterLines(s, it, occ.startMs) }, nowMs)
                val events = ArrayList<List<String>>(); events.add(lines); s.overrides.forEach { events.add(it.lines()) }
                SeriesChange.Replace(Doc(s.doc.head, events, s.doc.tail).render())
            }
            scope == Scope.This -> {
                val existing = s.overrides.firstOrNull { overrideStartMs(it) == occ.originalStartMs }
                val events = ArrayList<List<String>>()
                events.add(s.master.lines())
                for (o in s.overrides) {
                    if (o === existing) {
                        val startLike = o.prop("DTSTART")!!
                        val whenLines = edit.whenAt?.let { linesFor(it, startLike, o.prop("DTEND")) }
                        events.add(editedEvent(s.doc, o.lines(), noWhen, whenLines, nowMs))
                    } else events.add(o.lines())
                }
                if (existing == null) {
                    // a new override: a copy of the master for this occurrence, with its own start and end
                    val o = ev(s.master.lines())
                    listOf("RRULE", "RDATE", "EXDATE", "RECURRENCE-ID", "DTEND", "DURATION").forEach { o.remove(it) }
                    val startLike = s.master.prop("DTSTART")!!
                    val endLike = s.master.prop("DTEND") ?: startLike
                    o.replace("DTSTART", lineLike("DTSTART", startLike, occ.startMs))
                    o.insertAfter("DTSTART", lineLike("DTEND", endLike, occ.endMs))
                    o.insertAfter("UID", lineLike("RECURRENCE-ID", startLike, occ.originalStartMs))
                    val whenLines = edit.whenAt?.let { linesFor(it, startLike, s.master.prop("DTEND")) }
                    events.add(editedEvent(s.doc, o.lines(), noWhen, whenLines, nowMs))
                }
                SeriesChange.Replace(Doc(s.doc.head, events, s.doc.tail).render())
            }
            else -> split(s, occ.originalStartMs, edit, nowMs, newUid)
        }
    }

    /** "This and following": the earlier part keeps the object, the later part becomes a new object with a new UID. */
    private fun split(s: Series, instanceStartMs: Long, edit: EventEdit, nowMs: Long, newUid: String): SeriesChange {
        val old = (delete(s.doc.render(), instanceStartMs, Scope.Following, nowMs) as SeriesChange.Replace).ics

        val master = ev(s.master.lines())
        val startLike = master.prop("DTSTART")!!
        val endLike = master.prop("DTEND")
        master.replace("UID", IcsEdit.line("UID", newUid))
        master.remove("DURATION"); master.remove("DTEND")
        master.replace("DTSTART", lineLike("DTSTART", startLike, instanceStartMs))
        master.insertAfter("DTSTART", lineLike("DTEND", endLike ?: startLike, instanceStartMs + masterDurationMs(s)))
        filterExdates(master, s.form) { it >= instanceStartMs }
        // a COUNT is shared out: the occurrences already used by the earlier part are taken off
        val rr = master.prop("RRULE")!!
        val count = Regex("COUNT=(\\d+)", RegexOption.IGNORE_CASE).find(IcsEdit.value(rr))?.groupValues?.get(1)?.toInt()
        if (count != null) {
            val used = occurrencesBefore(s, instanceStartMs)
            val left = count - used
            require(left >= 1) { "no occurrences left for the later part" }
            val newRule = IcsEdit.value(rr).trim().split(';').joinToString(";") { p -> if (p.uppercase().startsWith("COUNT=")) "COUNT=$left" else p }
            master.replace("RRULE", IcsEdit.line("RRULE", newRule))
        }
        val whenLines = edit.whenAt?.let { linesFor(it, startLike, endLike) }
        val newMaster = editedEvent(s.doc, master.lines(), edit.copy(whenAt = null), whenLines, nowMs)
        val events = ArrayList<List<String>>(); events.add(newMaster)
        for (o in s.overrides) {
            if ((overrideStartMs(o) ?: -1L) >= instanceStartMs) { val moved = ev(o.lines()); moved.replace("UID", IcsEdit.line("UID", newUid)); events.add(moved.lines()) }
        }
        return SeriesChange.Split(old, Doc(s.doc.head, events, s.doc.tail).render(), newUid)
    }

    /** How many occurrences of the rule (EXDATE ignored, as COUNT counts them) start before [instanceStartMs]. */
    private fun occurrencesBefore(s: Series, instanceStartMs: Long): Int {
        val m = ev(s.master.lines())
        m.remove("EXDATE")
        m.replace("RRULE", IcsEdit.line("RRULE", ruleParts(m.prop("RRULE")!!).plus("UNTIL=" + untilBefore(s.form, instanceStartMs)).joinToString(";")))
        val ics = Doc(s.doc.head, mutableListOf(m.lines()), s.doc.tail).render()
        val found = Expander().expand(listOf(ObjectSource(1, "x", ics, "k", false)), s.masterStartMs - Expander.DAY_MS, instanceStartMs)
        return found.count { it.start < instanceStartMs }
    }
}
