package dev.bookah.calendar

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.bookah.calendar.data.RepeatInfo
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Words come from the string resources (English and Greek); tests give a fake with English templates. */
interface Texts {
    val locale: Locale
    fun get(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, n: Int): String
}

class ResTexts(private val res: Resources) : Texts {
    override val locale: Locale get() = res.configuration.locales[0]
    override fun get(id: Int, vararg args: Any): String = res.getString(id, *args)
    override fun plural(id: Int, n: Int): String = res.getQuantityString(id, n, n)
}

private val DAY_CODES = mapOf(
    "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
    "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
)

/** "Repeats weekly on Tuesday", "Repeats every 2 weeks on Monday, Friday, 10 times", ... in the app's language. */
fun repeatSummary(r: RepeatInfo, t: Texts, zone: ZoneId = ZoneId.systemDefault()): String {
    var s = when (r.frequency) {
        "DAILY" -> if (r.interval <= 1) t.get(R.string.repeat_daily) else t.get(R.string.repeat_every_days, r.interval)
        "WEEKLY" -> if (r.interval <= 1) t.get(R.string.repeat_weekly) else t.get(R.string.repeat_every_weeks, r.interval)
        "MONTHLY" -> if (r.interval <= 1) t.get(R.string.repeat_monthly) else t.get(R.string.repeat_every_months, r.interval)
        "YEARLY" -> if (r.interval <= 1) t.get(R.string.repeat_yearly) else t.get(R.string.repeat_every_years, r.interval)
        else -> return t.get(R.string.repeat_plain)
    }
    if (r.byDay.isNotEmpty()) s = t.get(R.string.repeat_on, s, r.byDay.joinToString(", ") { dayText(it, t) })
    val until = r.untilMillis
    if (r.count != null) s = t.get(R.string.repeat_count, s, r.count!!)
    else if (until != null) {
        val date = Instant.ofEpochMilli(until).atZone(zone).toLocalDate()
        s = t.get(R.string.repeat_until, s, DateTimeFormatter.ofPattern("d MMM yyyy", t.locale).format(date))
    }
    return s
}

private fun dayText(code: String, t: Texts): String {
    val day = DAY_CODES[code.takeLast(2)] ?: return code
    val name = day.getDisplayName(TextStyle.FULL, t.locale)
    val n = code.dropLast(2).toIntOrNull() ?: return name
    val ordinal = when (n) {
        1 -> R.string.ord_first; 2 -> R.string.ord_second; 3 -> R.string.ord_third; 4 -> R.string.ord_fourth; -1 -> R.string.ord_last
        else -> return name
    }
    return t.get(R.string.repeat_ordinal_day, t.get(ordinal), name)
}

/** "30 minutes before", "1 hour before", "1 day before", "At the time of the event" in the app's language. */
fun reminderText(minutesBefore: Int, t: Texts): String = when {
    minutesBefore == 0 -> t.get(R.string.reminder_at_time)
    minutesBefore % (7 * 24 * 60) == 0 -> t.plural(R.plurals.reminder_weeks, minutesBefore / (7 * 24 * 60))
    minutesBefore % (24 * 60) == 0 -> t.plural(R.plurals.reminder_days, minutesBefore / (24 * 60))
    minutesBefore % 60 == 0 -> t.plural(R.plurals.reminder_hours, minutesBefore / 60)
    minutesBefore > 60 -> t.get(R.string.reminder_hours_minutes, minutesBefore / 60, minutesBefore % 60)   // 1390 -> "23 h 10 min before"
    else -> t.plural(R.plurals.reminder_minutes, minutesBefore)
}
