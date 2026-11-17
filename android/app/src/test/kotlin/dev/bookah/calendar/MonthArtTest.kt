package dev.bookah.calendar

import dev.bookah.calendar.ui.MonthArt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Month

class MonthArtTest {
    @Test fun twelveMonthsUseTwelveDifferentPictures() {
        assertEquals(12, Month.values().map { MonthArt.resFor(it) }.toSet().size)
    }
}
