package dev.bookah.calendar.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ticking a task off and taking the tick back. The tick is written exactly as BookahTasks writes it (its TaskEdit.complete for a plain task):
 * STATUS, COMPLETED (UTC now) and PERCENT-COMPLETE:100, then DTSTAMP and LAST-MODIFIED to now and SEQUENCE one higher. Undo is a true undo:
 * STATUS and PERCENT-COMPLETE go back to what they were before the tick ([BeforeTick]; absent stays absent), COMPLETED goes, plus the same
 * DTSTAMP / LAST-MODIFIED / SEQUENCE update. Only for a task that [TaskEditability.of] calls editable.
 */
object TaskDone {
    /** STATUS and PERCENT-COMPLETE values before a tick; null = the line was not there. */
    data class BeforeTick(val status: String?, val percent: String?) {
        companion object {
            /** What BookahTasks' reopen writes when nothing is remembered: NEEDS-ACTION and no percent. */
            val Open = BeforeTick("NEEDS-ACTION", null)
        }
    }

    /** What a tick will overwrite, to be handed to [reopen] by the undo. */
    fun before(ics: String): BeforeTick = BeforeTick(TaskPatch.readProperty(ics, "STATUS"), TaskPatch.readProperty(ics, "PERCENT-COMPLETE"))

    fun complete(ics: String, now: Instant): String {
        val done = TaskPatch.setProperty(TaskPatch.setProperty(TaskPatch.setProperty(ics, "STATUS", "COMPLETED"), "COMPLETED", TaskPatch.stamp(now)), "PERCENT-COMPLETE", "100")
        return TaskPatch.touch(done, now)
    }

    fun reopen(ics: String, now: Instant, before: BeforeTick): String {
        val open = TaskPatch.setProperty(TaskPatch.setProperty(TaskPatch.setProperty(ics, "STATUS", before.status), "COMPLETED", null), "PERCENT-COMPLETE", before.percent)
        return TaskPatch.touch(open, now)
    }
}

/**
 * Giving a task WITHOUT any date a day from the calendar, and taking it back (undo). Written exactly as BookahTasks writes it when an undated task
 * is given a date (its Reschedule.plan sets a new start, TaskEdit.setDates writes it): DTSTART;VALUE=DATE:<yyyyMMdd>, no DUE, then DTSTAMP and
 * LAST-MODIFIED to now and SEQUENCE one higher. Undo takes DTSTART away again (plus the same touch): "no dates", as before.
 */
object TaskMove {
    private val DATE = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    private val MOVE_LINES = setOf("DTSTART", "DUE", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE")

    fun setDay(ics: String, date: java.time.LocalDate, now: Instant): String =
        TaskPatch.touch(TaskPatch.setProperty(TaskPatch.setProperty(ics, "DTSTART", DATE.format(date), params = "VALUE=DATE"), "DUE", null), now)

    fun clearDay(ics: String, now: Instant): String =
        TaskPatch.touch(TaskPatch.setProperty(TaskPatch.setProperty(ics, "DTSTART", null), "DUE", null), now)

    /** True when the task's start is exactly the date-only line [setDay] writes for [date] and it has no deadline (the version an undo may take back). */
    fun isOurMove(ics: String, date: java.time.LocalDate): Boolean = runCatching {
        TaskPatch.readLine(ics, "DTSTART")?.uppercase() == "DTSTART;VALUE=DATE:" + DATE.format(date) && TaskPatch.readLine(ics, "DUE") == null
    }.getOrDefault(false)

    /**
     * A start (DTSTART) or deadline (DUE) line in the task itself, read from the text: a date the calendar could not read still counts.
     * A DURATION line is not looked at here; [editability] refuses it on its own.
     */
    fun hasDates(ics: String): Boolean = TaskPatch.readLine(ics, "DTSTART") != null || TaskPatch.readLine(ics, "DUE") != null

    /** Parked for "someday" in BookahTasks: a CATEGORIES tag "someday" (any case, in any CATEGORIES line). */
    fun isParked(ics: String): Boolean = runCatching {
        TaskPatch.readAllProperties(ics, "CATEGORIES").flatMap { splitTags(it) }.any { it.equals("someday", ignoreCase = true) }
    }.getOrDefault(false)

    /**
     * May this task be given a day here? The same plain-task rules as the tick ([TaskEditability.of]: one VTODO, not repeating, no people, a
     * writable list; open subtasks do not matter for a date), and it must be open, not parked, and have no date of any kind. Last, a trial move
     * and undo must change nothing but the move's own lines.
     */
    fun editability(ics: String, listWritable: Boolean): TaskEditability {
        val base = TaskEditability.of(ics, listWritable, hasOpenSubtasks = false)
        if (base is TaskEditability.ReadOnly) return base
        if (TaskEditability.isFinished(ics)) return TaskEditability.ReadOnly(TaskReadOnlyReason.Finished)
        if (isParked(ics)) return TaskEditability.ReadOnly(TaskReadOnlyReason.Parked)
        val dated = runCatching { hasDates(ics) }.getOrNull() ?: return TaskEditability.ReadOnly(TaskReadOnlyReason.Unusual)
        if (dated) return TaskEditability.ReadOnly(TaskReadOnlyReason.HasDates)
        // a duration needs a start; giving one a start would quietly give it an end too
        if (TaskPatch.readLine(ics, "DURATION") != null) return TaskEditability.ReadOnly(TaskReadOnlyReason.Unusual)
        val ok = runCatching {
            val probe = java.time.LocalDate.of(2000, 1, 1)
            val moved = setDay(ics, probe, Instant.EPOCH)
            val back = clearDay(moved, Instant.EPOCH)
            isOurMove(moved, probe) && !hasDates(back) &&
                TaskEditability.onlyLinesDiffer(ics, moved, MOVE_LINES) && TaskEditability.onlyLinesDiffer(ics, back, MOVE_LINES)
        }.getOrDefault(false)
        return if (ok) TaskEditability.Editable else TaskEditability.ReadOnly(TaskReadOnlyReason.Unusual)
    }

    /** Splits a CATEGORIES value on commas that are not escaped, and unescapes each part (as BookahTasks' TaskEdit). */
    private fun splitTags(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '\\' && i + 1 < value.length -> { cur.append(value[i + 1]); i++ }
                c == ',' -> { out.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/**
 * Line-by-line edits of a task's stored text, copied from BookahTasks' IcsPatcher (only the parts the tick and the move need; the two apps stay separate).
 * Only the lines asked for change; every other line (alarms, X- lines of other apps, odd folding, the newline style) is kept byte for byte.
 * It works on the first VTODO that has no RECURRENCE-ID. Values are written as given.
 */
object TaskPatch {
    private val stampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun stamp(now: Instant): String = stampFormat.format(now)

    /** Folds a line to at most 75 octets per physical line (UTF-8), never inside a character. */
    fun fold(line: String, nl: String = "\r\n"): String {
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) { sb.append(nl).append(' '); bytes = 1 }
            sb.appendCodePoint(cp)
            bytes += size
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Logical lines (a folded line keeps its original physical text, folds included) and the newline style of the text. */
    private class Doc(val nl: String, val lines: MutableList<String>) {
        fun text() = lines.joinToString(nl) + nl
    }

    private fun parse(ics: String): Doc {
        val nl = if ("\r\n" in ics) "\r\n" else "\n"
        val logical = ArrayList<String>()
        for (p in ics.split(nl).dropLastWhile { it.isEmpty() }) {
            if ((p.startsWith(" ") || p.startsWith("\t")) && logical.isNotEmpty()) logical[logical.size - 1] = logical.last() + nl + p
            else logical.add(p)
        }
        return Doc(nl, logical)
    }

    private fun nameOf(line: String) = line.takeWhile { it != ':' && it != ';' }.uppercase()
    private fun unfold(logical: String) = logical.replace(Regex("\r?\n[ \t]"), "")

    /** Index of the BEGIN:VTODO line and of its END:VTODO line, for the first VTODO without RECURRENCE-ID. */
    private fun todoBounds(lines: List<String>): Pair<Int, Int> {
        var i = 0
        while (i < lines.size) {
            if (lines[i].equals("BEGIN:VTODO", ignoreCase = true)) {
                var j = i + 1
                var depth = 0
                var recurrence = false
                while (j < lines.size && !(depth == 0 && lines[j].equals("END:VTODO", ignoreCase = true))) {
                    val u = lines[j].uppercase()
                    when {
                        u.startsWith("BEGIN:") -> depth++
                        u.startsWith("END:") -> depth--
                        depth == 0 && u.startsWith("RECURRENCE-ID") -> recurrence = true
                    }
                    j++
                }
                if (!recurrence) return i to j
                i = j
            }
            i++
        }
        throw IllegalArgumentException("no VTODO to edit")
    }

    /** Indexes of the VTODO's own property lines, not those inside a nested block such as VALARM. */
    private fun topLevel(lines: List<String>, begin: Int, end: Int): List<Int> {
        val out = ArrayList<Int>()
        var depth = 0
        for (k in begin + 1 until end) {
            val u = lines[k].uppercase()
            when {
                u.startsWith("BEGIN:") -> depth++
                u.startsWith("END:") -> depth--
                depth == 0 -> out.add(k)
            }
        }
        return out
    }

    fun readProperty(ics: String, name: String): String? = readLine(ics, name)?.substringAfter(':')

    /** The whole first top-level line called [name] of the VTODO, unfolded, parameters included ("DTSTART;VALUE=DATE:20261120"), or null. */
    fun readLine(ics: String, name: String): String? {
        val doc = parse(ics)
        val (b, e) = todoBounds(doc.lines)
        return topLevel(doc.lines, b, e).map { doc.lines[it] }.firstOrNull { nameOf(it) == name.uppercase() }?.let { unfold(it) }
    }

    /** The values of every top-level line called [name] (a property may repeat: CATEGORIES), in order. */
    fun readAllProperties(ics: String, name: String): List<String> {
        val doc = parse(ics)
        val (b, e) = todoBounds(doc.lines)
        return topLevel(doc.lines, b, e).map { doc.lines[it] }.filter { nameOf(it) == name.uppercase() }.map { unfold(it).substringAfter(':') }
    }

    /**
     * Sets (or, with a null [value], removes) one single-valued property: every other line of the same name is dropped. An existing line is
     * replaced in place; a new one goes before the first nested block (a reminder), or before END:VTODO. The old line's parameters are
     * replaced by [params] ("VALUE=DATE"; empty = none), as in BookahTasks' IcsPatcher.
     */
    fun setProperty(ics: String, name: String, value: String?, params: String = ""): String {
        val doc = parse(ics)
        val (begin, end) = todoBounds(doc.lines)
        val key = name.uppercase()
        val mine = topLevel(doc.lines, begin, end).filter { nameOf(doc.lines[it]) == key }
        val newLine = value?.let { fold(key + (if (params.isEmpty()) "" else ";$params") + ":" + it, doc.nl) }
        if (mine.isEmpty()) {
            if (newLine != null) {
                val firstNested = (begin + 1 until end).firstOrNull { doc.lines[it].uppercase().startsWith("BEGIN:") } ?: end
                doc.lines.add(firstNested, newLine)
            }
        } else {
            for (k in mine.drop(1).reversed()) doc.lines.removeAt(k)
            if (newLine != null) doc.lines[mine.first()] = newLine else doc.lines.removeAt(mine.first())
        }
        return doc.text()
    }

    /** DTSTAMP and LAST-MODIFIED to [now], SEQUENCE one higher (as BookahTasks). */
    fun touch(ics: String, now: Instant): String {
        val s = stamp(now)
        val seq = (readProperty(ics, "SEQUENCE")?.trim()?.toIntOrNull() ?: 0) + 1
        return setProperty(setProperty(setProperty(ics, "DTSTAMP", s), "LAST-MODIFIED", s), "SEQUENCE", seq.toString())
    }
}
