package dev.bookah.calendar

import dev.bookah.calendar.data.Instance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SearchTest {
    private val today = LocalDate.of(2026, 11, 15)
    private fun day(n: Long) = today.plusDays(n)
    private fun ms(n: Long) = day(n).toEpochDay() * 86_400_000L + 36_000_000L

    private fun ev(title: String, n: Long, location: String? = null, notes: String? = null, href: String = "h$title$n") = Instance(
        1, "u", href, title, location, notes, ms(n), ms(n) + 3_600_000L, false, null, null, emptyList(), false,
    )

    private fun days(vararg e: Pair<Long, Instance>) = e.groupBy({ it.first }, { it.second }).map { (n, l) -> DayItems(day(n), l) }.sortedBy { it.date }

    @Test fun emptyQueryFindsNothing() = assertTrue(searchHits(days(0L to ev("Dentist", 0)), "  ", today).isEmpty())

    @Test fun wordsMatchTitlePlaceAndNotesInAnyOrder() {
        val d = days(1L to ev("Dentist", 1, "Ermou 10"), 2L to ev("Lunch", 2, notes = "with the dentist"), 3L to ev("Gym", 3))
        assertEquals(listOf("Dentist", "Lunch"), searchHits(d, "DENTIST", today).map { it.event.title })
        assertEquals(listOf("Dentist"), searchHits(d, "ermou dentist", today).map { it.event.title })
        assertTrue(searchHits(d, "ermou gym", today).isEmpty())
    }

    @Test fun greekAccentsAndFinalSigmaDoNotMatter() {
        val d = days(1L to ev("Καφές με τον Γιώργο", 1), 2L to ev("ΟΔΟΝΤΙΑΤΡΟΣ", 2))
        assertEquals(1, searchHits(d, "καφες", today).size)
        assertEquals(1, searchHits(d, "γιωργο", today).size)
        assertEquals(1, searchHits(d, "οδοντιατροσ", today).size)
        assertEquals(1, searchHits(d, "οδοντιατρος", today).size)
    }

    @Test fun comingEventsFirstSoonestFirst_thenPastMostRecentFirst() {
        val d = days(-5L to ev("Gym", -5), -1L to ev("Gym", -1), 0L to ev("Gym", 0), 7L to ev("Gym", 7), 2L to ev("Gym", 2))
        assertEquals(listOf(0L, 2L, 7L, -1L, -5L), searchHits(d, "gym", today).map { it.day.toEpochDay() - today.toEpochDay() })
    }

    @Test fun aMultiDayEventIsListedOnce() {
        val trip = ev("Trip", 1, href = "trip")
        val d = listOf(DayItems(day(1), listOf(trip)), DayItems(day(2), listOf(trip)), DayItems(day(3), listOf(trip)))
        assertEquals(1, searchHits(d, "trip", today).size)
    }

    @Test fun theLimitIsKept() {
        val d = days(*(0L..300L).map { it to ev("Gym", it) }.toTypedArray())
        assertEquals(200, searchHits(d, "gym", today).size)
        assertEquals(5, searchHits(d, "gym", today, limit = 5).size)
    }
}
