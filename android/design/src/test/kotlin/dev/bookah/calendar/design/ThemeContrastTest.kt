package dev.bookah.calendar.design

import org.junit.Assert.assertTrue
import org.junit.Test

/** The six picture-based families (and the id list) keep every text pair readable: 4.5:1 on all six surfaces. */
class ThemeContrastTest {
    private val pictureFamilies = listOf("sakura", "plum-snow", "hydrangea", "moon-viewing", "fireworks", "maple")

    @Test fun pictureFamiliesExistWithUniqueIds() {
        val ids = ThemeCatalog.all.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
        for (id in pictureFamilies) assertTrue("missing $id", id in ids)
    }

    @Test fun pictureFamiliesReachTheTextContrast() {
        for (id in pictureFamilies) for ((mode, t) in listOf("light" to ThemeCatalog.get(id).light, "dark" to ThemeCatalog.get(id).dark)) {
            for (s in t.surfaces) {
                for ((name, c) in listOf("text" to t.text, "text2" to t.text2, "muted" to t.textMuted, "accent" to t.accent, "secondary" to t.secondary)) {
                    assertTrue("$id $mode $name on a surface is ${Contrast.ratio(c, s)}", Contrast.ratio(c, s) >= 4.5)
                }
            }
            assertTrue("$id $mode onAccent", Contrast.ratio(t.onAccent, t.accent) >= 4.5)
            assertTrue("$id $mode onSecondary", Contrast.ratio(t.onSecondary, t.secondary) >= 4.5)
        }
    }
}
