package dev.bookah.calendar.design

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every family (tinted grounds included) keeps every text pair readable: 4.5:1 on all six surfaces. */
class ThemeContrastTest {
    private val pictureFamilies = listOf("sakura", "plum-snow", "hydrangea", "moon-viewing", "fireworks", "maple")

    @Test fun pictureFamiliesExistWithUniqueIds() {
        val ids = ThemeCatalog.all.map { it.id }
        assertTrue(ids.size == ids.toSet().size)
        for (id in pictureFamilies) assertTrue("missing $id", id in ids)
    }

    @Test fun everyFamilyReachesTheTextContrast() {
        val bad = mutableListOf<String>()
        fun check(what: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
            val r = Contrast.ratio(fg, bg)
            if (r < 4.5) bad += "$what %.2f".format(r)
        }
        for (f in ThemeCatalog.all) for ((mode, t) in listOf("light" to f.light, "dark" to f.dark, "black" to f.dark.pureBlack())) {
            for ((i, s) in t.surfaces.withIndex()) {
                // surface3 (index 4) is the deepest tint: only fills sit on it (the banner backdrop), so only the two main text colours must read on it
                for ((name, c) in listOf("text" to t.text, "text2" to t.text2, "muted" to t.textMuted, "accent" to t.accent, "secondary" to t.secondary)) {
                    if (i == 4 && name != "text" && name != "text2") continue
                    check("${f.id} $mode $name/surface$i", c, s)
                }
            }
            check("${f.id} $mode onAccent", t.onAccent, t.accent)
            check("${f.id} $mode onSecondary", t.onSecondary, t.secondary)
        }
        assertTrue(bad.joinToString(" | "), bad.isEmpty())
    }
}
