package dev.bookah.calendar

import dev.bookah.calendar.data.RepeatInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/** Checks the assembly of the sentences (order, which pieces appear); the real words live in the string resources. */
class RepeatTextTest {
    private val z = ZoneId.of("UTC")

    private val t = object : Texts {
        override val locale: Locale = Locale.ENGLISH
        override fun get(id: Int, vararg args: Any): String {
            val template = when (id) {
                R.string.repeat_daily -> "Repeats daily"; R.string.repeat_weekly -> "Repeats weekly"
                R.string.repeat_monthly -> "Repeats monthly"; R.string.repeat_yearly -> "Repeats yearly"
                R.string.repeat_every_weeks -> "Repeats every %1\$d weeks"
                R.string.repeat_on -> "%1\$s on %2\$s"; R.string.repeat_count -> "%1\$s, %2\$d times"; R.string.repeat_until -> "%1\$s, until %2\$s"
                R.string.repeat_ordinal_day -> "the %1\$s %2\$s"
                R.string.ord_last -> "last"; R.string.ord_first -> "first"
                R.string.reminder_at_time -> "At the time of the event"
                R.string.reminder_hours_minutes -> "%1\$d h %2\$d min before"
                else -> error("unexpected string id $id")
            }
            return String.format(template, *args)
        }
        override fun plural(id: Int, n: Int): String {
            val unit = when (id) {
                R.plurals.reminder_minutes -> "minute"; R.plurals.reminder_hours -> "hour"; R.plurals.reminder_days -> "day"; R.plurals.reminder_weeks -> "week"
                else -> error("unexpected plural id $id")
            }
            return if (n == 1) "1 $unit before" else "$n ${unit}s before"
        }
    }

    private fun s(r: RepeatInfo) = repeatSummary(r, t, z)

    @Test fun simpleWeekly() = assertEquals("Repeats weekly on Tuesday", s(RepeatInfo("WEEKLY", 1, listOf("TU"), null, null)))
    @Test fun everyTwoWeeksOnTwoDaysWithCount() =
        assertEquals("Repeats every 2 weeks on Monday, Friday, 10 times", s(RepeatInfo("WEEKLY", 2, listOf("MO", "FR"), 10, null)))
    @Test fun monthlyOrdinalDay() = assertEquals("Repeats monthly on the last Friday", s(RepeatInfo("MONTHLY", 1, listOf("-1FR"), null, null)))
    @Test fun dailyUntil() = assertEquals("Repeats daily, until 1 Dec 2026", s(RepeatInfo("DAILY", 1, emptyList(), null, 20_788L * 86_400_000L)))   // 1 Dec 2026, 00:00 UTC
    @Test fun yearly() = assertEquals("Repeats yearly", s(RepeatInfo("YEARLY", 1, emptyList(), null, null)))

    @Test fun reminders() {
        assertEquals("At the time of the event", reminderText(0, t))
        assertEquals("30 minutes before", reminderText(30, t))
        assertEquals("1 hour before", reminderText(60, t))
        assertEquals("2 days before", reminderText(2880, t))
        assertEquals("1 week before", reminderText(10080, t))
        assertEquals("1 h 30 min before", reminderText(90, t))
        assertEquals("23 h 10 min before", reminderText(1390, t))
    }
}
