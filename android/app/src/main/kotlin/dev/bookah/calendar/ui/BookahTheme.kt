package dev.bookah.calendar.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.compositeOver
import dev.bookah.calendar.design.Appearance
import dev.bookah.calendar.design.ThemeCatalog
import dev.bookah.calendar.design.ThemeColors
import dev.bookah.calendar.design.pureBlack

/** The theme tokens screens read directly (tiles, hairlines) beside the Material scheme. */
val LocalThemeColors = staticCompositionLocalOf { ThemeCatalog.get(ThemeCatalog.DEFAULT_ID).dark }

/** Maps one Hikyaku family onto Material 3 following docs/design-spec.md section 3.2. Washi & Ink and System appearance until Settings exists. */
@Composable
fun BookahTheme(familyId: String = ThemeCatalog.DEFAULT_ID, appearance: Appearance = Appearance.System, pureBlack: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.Dark -> true; Appearance.Light -> false; Appearance.System -> isSystemInDarkTheme() }
    val family = ThemeCatalog.get(familyId)
    val t: ThemeColors = if (dark) (if (pureBlack) family.dark.pureBlack() else family.dark) else family.light
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = t.bg, onBackground = t.text,
        surface = t.surface, onSurface = t.text, onSurfaceVariant = t.textMuted,
        surfaceContainer = t.raised, surfaceContainerHigh = t.sidebar, surfaceContainerLow = t.sidebar,
        primary = t.accent, onPrimary = t.onAccent,
        secondary = t.secondary, onSecondary = t.onSecondary,
        // selected drawer item and chips: a 20% tint of the secondary accent (docs/design-spec.md 3.2)
        secondaryContainer = t.secondary.copy(alpha = 0.2f).compositeOver(t.sidebar), onSecondaryContainer = t.text,
        outline = t.border, outlineVariant = t.border,
        error = t.danger,
    )
    // system bar icons follow the theme: light icons on a dark page, dark icons on a light page
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    CompositionLocalProvider(LocalThemeColors provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
