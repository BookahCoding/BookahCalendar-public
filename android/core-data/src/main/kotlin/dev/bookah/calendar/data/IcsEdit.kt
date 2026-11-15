package dev.bookah.calendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** When an event happens, as the form sets it. */
sealed interface EventWhen {
    data class Timed(val startMs: Long, val endMs: Long) : EventWhen

    /** [first] and [lastInclusive] are both shown days; the file gets the day after as DTEND (DTEND is exclusive). */
    data class AllDay(val first: LocalDate, val lastInclusive: LocalDate) : EventWhen
}

/** Everything the form can set for a NEW event. [reminderMinutes] are display reminders that many minutes before the start. */
data class EventDraft(
    val title: String, val whenAt: EventWhen, val location: String, val notes: String, val reminderMinutes: List<Int>,
    /** A repeating event. A timed one is then written in local time of [zoneId] with its VTIMEZONE, so 10:00 stays 10:00 across daylight saving. */
    val repeat: RepeatRule? = null,
    val zoneId: String? = null,
)

/**
 * The changes to an EXISTING event. A field left null is not touched: its lines stay exactly as they were in the file
 * (a changed title never rewrites the start time, its time zone or anything else).
 * For [location] and [notes] an empty text means "remove the property".
 */
data class EventEdit(
    val title: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val whenAt: EventWhen? = null,
    val reminderMinutes: List<Int>? = null,
) {
    val isEmpty get() = title == null && location == null && notes == null && whenAt == null && reminderMinutes == null
}

enum class ReadOnlyReason {
    /** A repeating event, an edited occurrence, or several events in one file: editing waits for Phase 4. */
    Repeating,

    /** Has an organiser or attendees: changing or deleting it could make the server send e-mails to other people. */
    Invitation,

    /** Not an event (a task, for example). */
    NotAnEvent,
}

sealed interface Editability {
    data object Editable : Editability

    /** A repeating event that is simple enough to change or delete (docs/phase-4-design.md 2.3): the user is asked which occurrences. */
    data object EditableSeries : Editability
    data class ReadOnly(val reason: ReadOnlyReason) : Editability
}

/** Text surgery on iCalendar objects: reads what the form needs, writes only what the user changed. No library re-writes the file. */
object IcsEdit {
    private const val CRLF = "\r\n"
    internal val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

    // ---------------------------------------------------------------- reading

    /** One element per logical property line; a folded line keeps its continuation lines inside the same element (bytes unchanged). */
    internal fun logicalLines(text: String): List<String> {
        val out = ArrayList<StringBuilder>()
        for (raw in text.split("\r\n", "\n")) {
            if (raw.isEmpty()) continue
            if ((raw[0] == ' ' || raw[0] == '\t') && out.isNotEmpty()) out.last().append(CRLF).append(raw) else out.add(StringBuilder(raw))
        }
        return out.map { it.toString() }
    }

    internal fun unfold(line: String) = line.replace(Regex("\r?\n[ \t]"), "")

    /** Index of the colon that ends the name and parameters (a colon inside a quoted parameter value does not count). */
    internal fun colonAt(unfolded: String): Int {
        var quoted = false
        for (i in unfolded.indices) {
            when (unfolded[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    internal fun name(line: String): String {
        val u = unfold(line)
        val end = u.indexOfAny(charArrayOf(':', ';')).let { if (it < 0) u.length else it }
        return u.substring(0, end).uppercase()
    }

    internal fun value(line: String): String {
        val u = unfold(line)
        val c = colonAt(u)
        return if (c < 0) "" else u.substring(c + 1)
    }

    internal fun params(line: String): String {
        val u = unfold(line)
        val c = colonAt(u)
        val head = if (c < 0) u else u.substring(0, c)
        return head.substringAfter(';', "").uppercase()
    }

    /** The structure of the first VEVENT: its direct property lines and its nested blocks (reminders). */
    internal class Parsed(val lines: List<String>, val begin: Int, val end: Int, val eventCount: Int, val body: List<Item>)

    /** A direct property of the event (one line) or a nested block such as a reminder (BEGIN ... END). */
    internal class Item(val name: String, val lines: List<String>, val isBlock: Boolean)

    internal fun parse(ics: String): Parsed {
        val lines = logicalLines(ics)
        var count = 0
        var begin = -1
        var end = -1
        for ((i, l) in lines.withIndex()) {
            when (name(l)) {
                "BEGIN" -> if (value(l).trim().uppercase() == "VEVENT") { count++; if (begin < 0) begin = i }
                "END" -> if (value(l).trim().uppercase() == "VEVENT" && end < 0 && begin >= 0) end = i
            }
        }
        val body = ArrayList<Item>()
        if (begin >= 0 && end > begin) {
            var i = begin + 1
            while (i < end) {
                val l = lines[i]
                if (name(l) == "BEGIN") {
                    val kind = value(l).trim().uppercase()
                    var j = i + 1
                    var nest = 1
                    while (j < end && nest > 0) {
                        if (name(lines[j]) == "BEGIN") nest++ else if (name(lines[j]) == "END") nest--
                        if (nest > 0) j++
                    }
                    body.add(Item(kind, lines.subList(i, minOf(j + 1, lines.size)).toList(), isBlock = true))
                    i = j + 1
                } else {
                    body.add(Item(name(l), listOf(l), isBlock = false))
                    i++
                }
            }
        }
        return Parsed(lines, begin, end, count, body)
    }

    /** Can the app change or delete this stored object safely? Judged on the whole file, not on one shown occurrence. */
    fun editability(ics: String): Editability {
        val p = parse(ics)
        if (p.eventCount == 0 || p.begin < 0 || p.end < 0) return Editability.ReadOnly(ReadOnlyReason.NotAnEvent)
        val names = p.body.filter { !it.isBlock }.map { it.name }.toSet()
        if (p.eventCount > 1 || names.any { it == "RRULE" || it == "RDATE" || it == "EXDATE" || it == "RECURRENCE-ID" }) {
            return Editability.ReadOnly(ReadOnlyReason.Repeating)
        }
        if (names.any { it == "ORGANIZER" || it == "ATTENDEE" }) return Editability.ReadOnly(ReadOnlyReason.Invitation)
        return Editability.Editable
    }

    /**
     * The reminders the form shows and manages: display reminders a whole number of minutes before the start.
     * Every other kind (e-mail, a fixed time, relative to the end, after the start) is never shown and never touched.
     */
    fun managedReminderMinutes(ics: String): List<Int> =
        parse(ics).body.filter { it.isBlock && it.name == "VALARM" }.mapNotNull { managedMinutes(it) }.distinct().sortedDescending()

    private fun managedMinutes(alarm: Item): Int? {
        var action: String? = null
        var trigger: String? = null
        var triggerParams = ""
        for (l in alarm.lines) when (name(l)) {
            "ACTION" -> action = value(l).trim().uppercase()
            "TRIGGER" -> { trigger = value(l).trim().uppercase(); triggerParams = params(l) }
        }
        if (action != "DISPLAY" || trigger == null) return null
        if ("VALUE=DATE-TIME" in triggerParams || "RELATED=END" in triggerParams) return null
        return minutesBefore(trigger)
    }

    /** "-PT15M" -> 15, "-P1D" -> 1440, "-PT1H30M" -> 90, "PT0S" -> 0; null for anything after the start or not whole minutes. */
    internal fun minutesBefore(trigger: String): Int? {
        val m = Regex("^([+-]?)P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").matchEntire(trigger) ?: return null
        val (sign, w, d, h, min, s) = m.destructured
        val seconds = (s.toLongOrNull() ?: 0L)
        if (seconds % 60 != 0L) return null
        val total = ((w.toLongOrNull() ?: 0L) * 7 * 24 * 60) + ((d.toLongOrNull() ?: 0L) * 24 * 60) + ((h.toLongOrNull() ?: 0L) * 60) + (min.toLongOrNull() ?: 0L) + seconds / 60
        if (total == 0L) return 0
        if (sign != "-") return null                       // after the start
        return if (total > Int.MAX_VALUE) null else total.toInt()
    }

    // ---------------------------------------------------------------- writing

    /** Why a draft cannot be saved, or null when it can. */
    fun validate(whenAt: EventWhen): String? = when (whenAt) {
        is EventWhen.Timed -> if (whenAt.endMs < whenAt.startMs) "end before start" else null
        is EventWhen.AllDay -> if (whenAt.lastInclusive.isBefore(whenAt.first)) "end before start" else null
    }

    internal fun escape(text: String) = text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** A property line, folded at 75 bytes the way the standard asks (never inside a multi-byte character). */
    internal fun line(name: String, value: String, parameters: String = ""): String {
        val full = name + (if (parameters.isEmpty()) "" else ";$parameters") + ":" + value
        val out = StringBuilder()
        var bytes = 0
        for (cp in full.codePoints().toArray()) {
            val s = String(Character.toChars(cp))
            val n = s.toByteArray(Charsets.UTF_8).size
            if (bytes + n > 75) { out.append(CRLF).append(' '); bytes = 1 }   // a continuation line starts with one space
            out.append(s)
            bytes += n
        }
        return out.toString()
    }

    internal fun utc(ms: Long) = UTC_FORMAT.format(Instant.ofEpochMilli(ms))

    private fun whenLines(w: EventWhen): List<String> = when (w) {
        is EventWhen.Timed -> listOf(line("DTSTART", utc(w.startMs)), line("DTEND", utc(w.endMs)))
        is EventWhen.AllDay -> listOf(
            line("DTSTART", DATE_FORMAT.format(w.first), "VALUE=DATE"),
            line("DTEND", DATE_FORMAT.format(w.lastInclusive.plusDays(1)), "VALUE=DATE"),   // exclusive end
        )
    }

    private fun triggerFor(minutes: Int): String = when {
        minutes == 0 -> "PT0S"
        minutes % (7 * 24 * 60) == 0 -> "-P${minutes / (7 * 24 * 60)}W"
        minutes % (24 * 60) == 0 -> "-P${minutes / (24 * 60)}D"
        minutes % 60 == 0 -> "-PT${minutes / 60}H"
        else -> "-PT${minutes}M"
    }

    private fun alarmLines(minutes: Int): List<String> =
        listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder", "TRIGGER:${triggerFor(minutes)}", "END:VALARM")

    /** The file for a brand-new event. Times are written in UTC (no time-zone data needed); there are no attendees, so no invitations go out. */
    fun newEvent(uid: String, draft: EventDraft, nowMs: Long): String {
        require(validate(draft.whenAt) == null) { "end before start" }
        val rule = draft.repeat
        val zone = draft.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }?.takeIf { it !is ZoneOffset } ?: ZoneOffset.UTC
        var whenAt = draft.whenAt
        if (rule != null) {
            val firstDay = when (val w = whenAt) {
                is EventWhen.Timed -> Instant.ofEpochMilli(w.startMs).atZone(zone).toLocalDate()
                is EventWhen.AllDay -> w.first
            }
            require(rule.problem(firstDay) == null) { "bad repeat" }
            val shift = RepeatWriter.startShiftDays(rule, firstDay)      // a weekly rule that skips the start's weekday starts on its first chosen day
            if (shift != 0L) whenAt = when (val w = whenAt) {
                is EventWhen.Timed -> EventWhen.Timed(w.startMs + shift * 86_400_000L, w.endMs + shift * 86_400_000L)
                is EventWhen.AllDay -> EventWhen.AllDay(w.first.plusDays(shift), w.lastInclusive.plusDays(shift))
            }
        }
        val l = ArrayList<String>()
        l += "BEGIN:VCALENDAR"; l += "VERSION:2.0"; l += "PRODID:-//BookahCalendar//EN"
        val local = rule != null && whenAt is EventWhen.Timed && zone != ZoneOffset.UTC
        if (local) l += RepeatWriter.vtimezone(zone, nowMs)
        l += "BEGIN:VEVENT"
        l += line("UID", uid); l += line("DTSTAMP", utc(nowMs)); l += line("CREATED", utc(nowMs)); l += line("LAST-MODIFIED", utc(nowMs))
        val w = whenAt
        if (local && w is EventWhen.Timed) {
            l += line("DTSTART", RepeatWriter.localText(w.startMs, zone), "TZID=${zone.id}")
            l += line("DTEND", RepeatWriter.localText(w.endMs, zone), "TZID=${zone.id}")
        } else l += whenLines(whenAt)
        if (rule != null) l += line("RRULE", RepeatWriter.rrule(rule, whenAt is EventWhen.AllDay, zone))
        l += line("SUMMARY", escape(draft.title))
        if (draft.location.isNotBlank()) l += line("LOCATION", escape(draft.location))
        if (draft.notes.isNotBlank()) l += line("DESCRIPTION", escape(draft.notes))
        l += "SEQUENCE:0"
        for (m in draft.reminderMinutes.distinct()) l += alarmLines(m)
        l += "END:VEVENT"; l += "END:VCALENDAR"
        return l.joinToString(CRLF) + CRLF
    }

    /**
     * The file after the user's changes. Only the fields in [edit] are rewritten; every other line stays as it was.
     * Returns the text unchanged when [edit] is empty. Refuses (IllegalStateException) a file that is not [Editability.Editable].
     */
    fun applyEdit(ics: String, edit: EventEdit, nowMs: Long): String = applyEditCore(ics, edit, nowMs, null, requireEditable = true)

    /**
     * The same edit for the series code: [whenLines] (ready-made DTSTART and DTEND lines, in the time-zone form the object already uses) replace
     * the start and end instead of UTC lines built from [edit]`.whenAt`, and [requireEditable] = false allows an object that is part of a series
     * (the caller has already checked the series rules and works on one event cut out of the object).
     */
    internal fun applyEditCore(ics: String, edit: EventEdit, nowMs: Long, whenLines: List<String>?, requireEditable: Boolean, forceTouch: Boolean = false): String {
        if (edit.isEmpty && whenLines == null && !forceTouch) return ics
        if (requireEditable) check(editability(ics) is Editability.Editable) { "this event cannot be edited" }
        if (whenLines == null) edit.whenAt?.let { require(validate(it) == null) { "end before start" } }
        val p = parse(ics)
        val body = p.body.toMutableList()

        fun replaceProp(propName: String, newLine: String?) {
            val first = body.indexOfFirst { !it.isBlock && it.name == propName }
            body.removeAll { !it.isBlock && it.name == propName }
            if (newLine == null) return
            val item = Item(propName, listOf(newLine), false)
            if (first >= 0) body.add(minOf(first, body.size), item)
            else { val blockAt = body.indexOfFirst { it.isBlock }; body.add(if (blockAt >= 0) blockAt else body.size, item) }
        }

        edit.title?.let { replaceProp("SUMMARY", line("SUMMARY", escape(it))) }
        edit.location?.let { replaceProp("LOCATION", if (it.isBlank()) null else line("LOCATION", escape(it))) }
        edit.notes?.let { replaceProp("DESCRIPTION", if (it.isBlank()) null else line("DESCRIPTION", escape(it))) }

        (whenLines ?: edit.whenAt?.let { whenLines(it) })?.let { newLines ->
            // the old start, end and duration go together (an event never has both an end and a duration)
            val at = body.indexOfFirst { !it.isBlock && (it.name == "DTSTART" || it.name == "DTEND" || it.name == "DURATION") }
            body.removeAll { !it.isBlock && (it.name == "DTSTART" || it.name == "DTEND" || it.name == "DURATION") }
            val fresh = newLines.map { Item(name(it), listOf(it), false) }
            val into = if (at >= 0) minOf(at, body.size) else (body.indexOfFirst { it.isBlock }.let { if (it >= 0) it else body.size })
            body.addAll(into, fresh)
        }

        edit.reminderMinutes?.let { wanted ->
            val want = wanted.distinct().toSet()
            val have = HashSet<Int>()
            val it = body.listIterator()
            while (it.hasNext()) {
                val item = it.next()
                if (!item.isBlock || item.name != "VALARM") continue
                val m = managedMinutes(item) ?: continue                // other kinds of reminder are never touched
                if (m in want && have.add(m)) continue                  // keep the existing one as it is
                it.remove()                                             // removed by the user, or a duplicate of one we keep
            }
            for (m in want.sorted().reversed()) if (m !in have) body.add(Item("VALARM", alarmLines(m), true))
        }

        // housekeeping: the change time, and the version counter
        replaceProp("DTSTAMP", line("DTSTAMP", utc(nowMs)))
        replaceProp("LAST-MODIFIED", line("LAST-MODIFIED", utc(nowMs)))
        val seq = body.firstOrNull { !it.isBlock && it.name == "SEQUENCE" }?.let { value(it.lines[0]).trim().toIntOrNull() } ?: 0
        replaceProp("SEQUENCE", "SEQUENCE:${seq + 1}")

        val out = ArrayList<String>()
        out.addAll(p.lines.subList(0, p.begin + 1))
        for (item in body) out.addAll(item.lines)
        out.addAll(p.lines.subList(p.end, p.lines.size))
        return out.joinToString(CRLF) + CRLF
    }

    /** The UID of the single event in the file, for display and checks. */
    fun uid(ics: String): String? = parse(ics).body.firstOrNull { !it.isBlock && it.name == "UID" }?.let { value(it.lines[0]).trim() }
}
