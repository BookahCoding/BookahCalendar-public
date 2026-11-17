package dev.bookah.calendar

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bookah.calendar.design.Appearance
import dev.bookah.calendar.design.ThemeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek

/** Choices that are not secrets: look and week start. (Secrets live in the Keystore vault.) */
data class Prefs(
    val themeId: String = ThemeCatalog.DEFAULT_ID,
    val appearance: Appearance = Appearance.System,
    /** Dark themes with a black page (easier on OLED screens). */
    val pureBlack: Boolean = false,
    /** First day of the week in Month (the design spec starts on Monday). */
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("prefs")

class PrefsStore(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val appearance = stringPreferencesKey("appearance")
    private val weekStart = intPreferencesKey("week_start")
    private val pureBlack = booleanPreferencesKey("pure_black")

    val flow: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            themeId = ThemeCatalog.get(p[theme] ?: ThemeCatalog.DEFAULT_ID).id,
            appearance = runCatching { Appearance.valueOf(p[appearance] ?: "") }.getOrDefault(Appearance.System),
            pureBlack = p[pureBlack] ?: false,
            weekStart = runCatching { DayOfWeek.of(p[weekStart] ?: 1) }.getOrDefault(DayOfWeek.MONDAY),
        )
    }

    suspend fun setTheme(id: String) = context.dataStore.edit { it[theme] = ThemeCatalog.get(id).id }
    suspend fun setAppearance(a: Appearance) = context.dataStore.edit { it[appearance] = a.name }
    suspend fun setPureBlack(on: Boolean) = context.dataStore.edit { it[pureBlack] = on }
    suspend fun setWeekStart(d: DayOfWeek) = context.dataStore.edit { it[weekStart] = d.value }
}
