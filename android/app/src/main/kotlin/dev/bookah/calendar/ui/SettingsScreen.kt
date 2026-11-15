package dev.bookah.calendar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.bookah.calendar.BuildConfig
import dev.bookah.calendar.Reminder
import dev.bookah.calendar.ReminderNotifier
import dev.bookah.calendar.CrashLog
import dev.bookah.calendar.R
import dev.bookah.calendar.UiState
import dev.bookah.calendar.design.Appearance
import dev.bookah.calendar.design.ThemeCatalog
import java.time.DayOfWeek

/**
 * Settings: week start, appearance and theme gallery, read-only feeds, account, updates, About and the crash log.
 * The feed address is typed into a hidden field, is not kept in the saved screen state, and is never shown again.
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onAddFeed: (name: String, address: String) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onSignOut: () -> Unit,
    onTheme: (String) -> Unit,
    onAppearance: (Appearance) -> Unit,
    onWeekStart: (DayOfWeek) -> Unit,
    onSearchWeatherPlace: (String) -> Unit,
    onChooseWeatherPlace: (dev.bookah.calendar.data.Place) -> Unit,
    onRemoveWeatherPlace: () -> Unit,
    onCheckUpdates: () -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(stringResource(R.string.settings), back = true, onNav = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Section(R.string.settings_general)
            Text(stringResource(R.string.week_start), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((day, label) in listOf(DayOfWeek.MONDAY to R.string.day_monday, DayOfWeek.SUNDAY to R.string.day_sunday, DayOfWeek.SATURDAY to R.string.day_saturday)) {
                    FilterChip(state.prefs.weekStart == day, { onWeekStart(day) }, { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.appearance), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((a, label) in listOf(Appearance.Light to R.string.appearance_light, Appearance.Dark to R.string.appearance_dark, Appearance.System to R.string.appearance_system)) {
                    FilterChip(state.prefs.appearance == a, { onAppearance(a) }, { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
            for (family in ThemeCatalog.all) {
                val sample = if (state.prefs.appearance == Appearance.Dark) family.dark else family.light
                Row(Modifier.fillMaxWidth().clickable { onTheme(family.id) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(state.prefs.themeId == family.id, { onTheme(family.id) })
                    // a little preview of the family: its paper, its two accents
                    Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in listOf(sample.bg, sample.accent, sample.secondary)) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(c).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(family.name, style = MaterialTheme.typography.bodyLarge)
                        Text(themeDescription(family.id, family.inspiration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.settings_feeds)
            Text(stringResource(R.string.settings_feeds_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (feed in state.feeds) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(feed.name, style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.feed_stored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ onRemoveFeed(feed.id) }) { Text(stringResource(R.string.feed_remove)) }
                }
            }
            if (state.hiddenDuplicates > 0) {
                Text(stringResource(R.string.hidden_duplicates, state.hiddenDuplicates), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !state.feedBusy, label = { Text(stringResource(R.string.feed_name)) })
            OutlinedTextField(
                address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !state.feedBusy,
                label = { Text(stringResource(R.string.feed_address)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            state.feedMessage?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium) }
            Button(
                { onAddFeed(name, address); address = "" }, Modifier.fillMaxWidth(),
                enabled = !state.feedBusy && address.isNotBlank(),
            ) { Text(if (state.feedBusy) stringResource(R.string.status_syncing) else stringResource(R.string.feed_add)) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.weather)
            Text(stringResource(R.string.weather_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val place = state.weatherPlace
            if (place != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(place.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton({ onRemoveWeatherPlace() }) { Text(stringResource(R.string.feed_remove)) }
                }
            } else {
                var query by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !state.weatherBusy,
                    label = { Text(stringResource(R.string.weather_place)) },
                )
                Button({ onSearchWeatherPlace(query) }, Modifier.fillMaxWidth(), enabled = !state.weatherBusy && query.isNotBlank()) {
                    Text(if (state.weatherBusy) stringResource(R.string.status_syncing) else stringResource(R.string.weather_search))
                }
                state.weatherMessage?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium) }
                for (found in state.weatherResults.orEmpty()) {
                    Text(
                        found.label,
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChooseWeatherPlace(found) }.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.reminders)
            var allowed by remember { mutableStateOf(ReminderNotifier.notificationsAllowed(context)) }
            val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = ReminderNotifier.notificationsAllowed(context) }
            Text(
                stringResource(if (allowed) R.string.reminders_on else R.string.reminders_off),
                style = MaterialTheme.typography.bodyMedium, color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.reminders_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!allowed) {
                OutlinedButton({
                    if (android.os.Build.VERSION.SDK_INT >= 33) askPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text(stringResource(R.string.reminders_allow)) }
            }
            OutlinedButton({
                val now = System.currentTimeMillis()
                ReminderNotifier.show(context, Reminder("test", now, context.getString(R.string.reminders_test_title), now + 3_600_000L, now + 7_200_000L, false, null), id = 424242)
                allowed = ReminderNotifier.notificationsAllowed(context)
            }) { Text(stringResource(R.string.reminders_test)) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.settings_account)
            OutlinedButton(onSignOut) { Text(stringResource(R.string.action_sign_out)) }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.updates)
            Text(
                if (BuildConfig.RELEASES_REPO.isBlank()) stringResource(R.string.updates_off) else stringResource(R.string.updates_on),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (BuildConfig.RELEASES_REPO.isNotBlank()) {
                OutlinedButton(onCheckUpdates, enabled = !state.updateBusy) {
                    Text(stringResource(if (state.updateBusy) R.string.updates_checking else R.string.updates_check))
                }
                when (val u = state.updateResult) {
                    is dev.bookah.calendar.data.UpdateResult.UpToDate -> Text(stringResource(R.string.updates_latest, u.version), style = MaterialTheme.typography.bodyMedium)
                    is dev.bookah.calendar.data.UpdateResult.Available -> {
                        Text(stringResource(R.string.updates_available, u.version), style = MaterialTheme.typography.bodyMedium)
                        val ctx = LocalContext.current
                        Button({ runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u.pageUrl))) } }) {
                            Text(stringResource(R.string.updates_open))
                        }
                    }
                    dev.bookah.calendar.data.UpdateResult.Unreachable -> Text(stringResource(R.string.updates_unreachable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    dev.bookah.calendar.data.UpdateResult.Failed -> Text(stringResource(R.string.updates_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    null -> {}
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.speed)
            val sp = state.speed
            Text(
                stringResource(R.string.speed_start, sp.coldStartMs?.toString() ?: "–") + "\n" +
                    stringResource(R.string.speed_expand, sp.expandMs?.toString() ?: "–") + "\n" +
                    stringResource(R.string.speed_sync, sp.syncMs?.toString() ?: "–"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Section(R.string.about)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.about_credits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var showLicences by remember { mutableStateOf(false) }
            TextButton({ showLicences = !showLicences }) { Text(stringResource(R.string.licences)) }
            if (showLicences) {
                // the full licence texts (MIT, BSD-2-Clause, Apache-2.0) as the licences require
                val text = remember { context.resources.openRawResource(R.raw.licenses).bufferedReader().use { it.readText() } }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section(R.string.crash_log)
            Text(
                crash.ifBlank { stringResource(R.string.crash_none) }.take(4000),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (crash.isNotBlank()) TextButton({ CrashLog.clear(context); crash = "" }) { Text(stringResource(R.string.crash_clear)) }
            Box(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Section(title: Int) = Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

/** The one-line description of a theme in the app's language (the catalogue itself only holds English). */
@Composable
private fun themeDescription(id: String, fallback: String): String {
    val res = when (id) {
        "washi-ink" -> R.string.theme_desc_washi_ink
        "great-wave" -> R.string.theme_desc_great_wave
        "red-fuji" -> R.string.theme_desc_red_fuji
        "pine-mist" -> R.string.theme_desc_pine_mist
        "chrysanthemum" -> R.string.theme_desc_chrysanthemum
        "peony" -> R.string.theme_desc_peony
        "kirifuri" -> R.string.theme_desc_kirifuri
        "thunderstorm" -> R.string.theme_desc_thunderstorm
        "irises" -> R.string.theme_desc_irises
        "shibuya-night" -> R.string.theme_desc_shibuya_night
        "akihabara" -> R.string.theme_desc_akihabara
        else -> return fallback
    }
    return stringResource(res)
}
