package dev.bookah.calendar.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pill colours of events AND tasks (tasks use the same ChipColor.forCalendar with their list's colour): the writing on a pill (title, time,
 * the "Late" label, the tick symbol, the Pick a day button) is black or white with at least 4.5:1 contrast, whatever colour a list has.
 */
class ChipColorTest {
    @Test fun everyListColourGivesReadableWritingOnItsPill() {
        var worst = 21.0
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val chip = ChipColor.forCalendar(Color(r / 255f, g / 255f, b / 255f, 1f))
            assertTrue(chip.text == Color.White || chip.text == Color.Black)
            worst = minOf(worst, Contrast.ratio(chip.text, chip.fill))
        }
        assertTrue("worst contrast $worst", worst >= ChipColor.MIN)
    }

    @Test fun theGeneratedListColoursOfTheDebugSeedAreReadable() {
        for (hex in listOf("#00679E", "#E9D859", "#7A7A7A", "#3E8E41", "#C0504D", "#8E6E3E", "#7A5CA8")) {
            val chip = ChipColor.forCalendar(ChipColor.parse(hex))
            assertTrue(hex, Contrast.ratio(chip.text, chip.fill) >= ChipColor.MIN)
        }
    }
}
